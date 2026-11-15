package com.exchangex.api;

import com.exchangex.engine.MatchingEngine;
import com.exchangex.engine.Order;
import com.exchangex.engine.OrderView;
import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
}