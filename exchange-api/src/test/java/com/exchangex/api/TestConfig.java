package com.exchangex.api;

import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.engine.MatchingEngine;
import com.exchangex.engine.Order;
import com.exchangex.engine.OrderView;
import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Profile("test & !postgres-integration")
@Configuration
public class TestConfig {

    @Bean
    @Primary
    DurableCommandCoordinator durableCommandCoordinator(MatchingEngine matchingEngine) {
        // Use no-arg TransactionTemplate constructor for test - doesn't need transaction manager
        return new TestDurableCommandCoordinator(matchingEngine, new TransactionTemplate());
    }

    @Bean
    @Primary
    CommandRepository commandRepository() {
        return new TestCommandRepository();
    }

    @Bean
    @Primary
    CheckpointRepository checkpointRepository() {
        return new TestCheckpointRepository();
    }

    @Bean
    @Primary
    TradeRepository tradeRepository() {
        return new TestTradeRepository();
    }

    @Bean
    @Primary
    DataSource dataSource() {
        // Use H2 embedded database for tests - no cleanup script as tables don't exist in test
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
    }

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Test implementation that delegates to the in-memory MatchingEngine
     * for backwards compatibility with existing tests.
     * Overrides initialize() and close() to avoid using null repositories.
     */
    static class TestDurableCommandCoordinator extends DurableCommandCoordinator {

        private final MatchingEngine engine;
        private final AtomicLong sequenceGenerator = new AtomicLong(1);

        TestDurableCommandCoordinator(MatchingEngine engine, TransactionTemplate transactionTemplate) {
            super(engine, null, null, null, null, null, transactionTemplate);
            this.engine = engine;
        }

        @Override
        public void initialize() {
            // No-op for test - skip database initialization
        }

        @Override
        public CompletableFuture<AdmissionResult> submitOrder(long clientOrderId, Side side, long price, long quantity) {
            Order order = new Order(clientOrderId, "AAPL", side, price, quantity);
            List<Trade> trades = engine.submit(order);
            long seq = sequenceGenerator.getAndIncrement();
            return CompletableFuture.completedFuture(new AdmissionResult(seq, false));
        }

        @Override
        public CompletableFuture<AdmissionResult> cancelOrder(long clientOrderId) {
            boolean cancelled = engine.cancel(clientOrderId);
            long seq = sequenceGenerator.getAndIncrement();
            return CompletableFuture.completedFuture(new AdmissionResult(seq, false));
        }

        @Override
        public CompletableFuture<Optional<OrderView>> findOrder(long clientOrderId) {
            return CompletableFuture.completedFuture(engine.find(clientOrderId));
        }

        @Override
        public CompletableFuture<BookSnapshot> snapshot() {
            return CompletableFuture.completedFuture(
                    new BookSnapshot(engine.bestBid(), engine.bestAsk())
            );
        }

        @Override
        public CompletableFuture<List<Trade>> trades() {
            return CompletableFuture.completedFuture(engine.trades());
        }

        @Override
        public void close() {
            // No-op for test
        }
    }

    // ============================================================
    // Test Repository Implementations
    // ============================================================

    static class TestCommandRepository implements CommandRepository {
        private final ConcurrentHashMap<Long, CommandRecord> commands = new ConcurrentHashMap<>();
        private final AtomicLong sequenceGenerator = new AtomicLong(1);

        @Override
        public long insertSubmitCommand(long clientOrderId, String symbol, Side side, long price, long quantity) {
            long seq = sequenceGenerator.getAndIncrement();
            commands.put(seq, new CommandRecord(seq, "SUBMIT", clientOrderId, symbol, side, price, quantity, "PENDING"));
            return seq;
        }

        @Override
        public long insertCancelCommand(long clientOrderId, String symbol) {
            long seq = sequenceGenerator.getAndIncrement();
            commands.put(seq, new CommandRecord(seq, "CANCEL", clientOrderId, symbol, null, null, null, "PENDING"));
            return seq;
        }

        @Override
        public Optional<CommandRecord> findPendingSubmitByClientOrderId(long clientOrderId, String symbol) {
            return commands.values().stream()
                    .filter(c -> c.clientOrderId() == clientOrderId && c.symbol().equals(symbol)
                            && c.commandType().equals("SUBMIT") && c.status().equals("PENDING"))
                    .findFirst();
        }

        @Override
        public Optional<CommandRecord> findPendingCancelByClientOrderId(long clientOrderId, String symbol) {
            return commands.values().stream()
                    .filter(c -> c.clientOrderId() == clientOrderId && c.symbol().equals(symbol)
                            && c.commandType().equals("CANCEL") && (c.status().equals("PENDING") || c.status().equals("FAILED")))
                    .findFirst();
        }

        @Override
        public List<CommandRecord> findPendingCommandsSince(String symbol, long lastCompletedSequenceId) {
            return commands.values().stream()
                    .filter(c -> c.symbol().equals(symbol) && c.sequenceId() > lastCompletedSequenceId
                            && (c.status().equals("PENDING") || c.status().equals("FAILED")))
                    .sorted((a, b) -> Long.compare(a.sequenceId(), b.sequenceId()))
                    .toList();
        }

        @Override
        public long countOutstanding(String symbol) {
            return commands.values().stream()
                    .filter(c -> c.symbol().equals(symbol) && (c.status().equals("PENDING") || c.status().equals("FAILED")))
                    .count();
        }

        @Override
        public void markCompleted(long sequenceId) {
            commands.computeIfPresent(sequenceId, (k, v) -> new CommandRecord(
                    v.sequenceId(), v.commandType(), v.clientOrderId(), v.symbol(), v.side(), v.price(), v.quantity(), "COMPLETED"));
        }

        @Override
        public void markFailed(long sequenceId) {
            commands.computeIfPresent(sequenceId, (k, v) -> new CommandRecord(
                    v.sequenceId(), v.commandType(), v.clientOrderId(), v.symbol(), v.side(), v.price(), v.quantity(), "FAILED"));
        }

        @Override
        public Optional<CommandRecord> findBySequenceId(long sequenceId) {
            return Optional.ofNullable(commands.get(sequenceId));
        }

        @Override
        public void deleteAll() {
            commands.clear();
            sequenceGenerator.set(1);
        }
    }

    static class TestCheckpointRepository implements CheckpointRepository {
        private final AtomicLong lastCheckpoint = new AtomicLong(0);

        @Override
        public long getLastCompletedSequenceId(String symbol) {
            return lastCheckpoint.get();
        }

        @Override
        public boolean advanceIfGreater(String symbol, long newSequenceId) {
            long current = lastCheckpoint.get();
            if (newSequenceId > current) {
                return lastCheckpoint.compareAndSet(current, newSequenceId);
            }
            return false;
        }

        @Override
        public void reset() {
            lastCheckpoint.set(0);
        }
    }

    static class TestTradeRepository implements TradeRepository {
        private final ConcurrentHashMap<Long, TradeRecord> trades = new ConcurrentHashMap<>();
        private final AtomicLong idGenerator = new AtomicLong(1);

        @Override
        public void insertTrades(List<TradeRecord> tradeRecords) {
            for (TradeRecord trade : tradeRecords) {
                long id = idGenerator.getAndIncrement();
                trades.put(id, new TradeRecord(
                        id, trade.buyOrderId(), trade.sellOrderId(), trade.executionPrice(),
                        trade.executionQuantity(), trade.buySequenceId(), trade.sellSequenceId()));
            }
        }

        @Override
        public List<TradeRecord> findBySymbol(String symbol) {
            // Test implementation doesn't track symbol on trade record, return all
            return List.copyOf(trades.values());
        }

        @Override
        public void deleteAll() {
            trades.clear();
            idGenerator.set(1);
        }
    }
}