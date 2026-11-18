package com.exchangex.api.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort, process-local STOMP publisher. PostgreSQL is authoritative. No
 * broker acknowledgement or WS message may alter commands, trades or balances.
 * Clients MUST resync via HTTP after reconnect and while disconnected.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "exchangex.stream.enabled", havingValue = "true", matchIfMissing = true)
public class MarketStreamService {
    private static final Logger log = LoggerFactory.getLogger(MarketStreamService.class);
    private static final int MAX_PRIVATE_ORDERS = 5000;
    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate socket;
    private final Map<String, Long> lastPublished = new HashMap<>();
    private final Map<Long, String> lastPrivateState = new HashMap<>();

    public MarketStreamService(JdbcTemplate jdbc, SimpMessagingTemplate socket) {
        this.jdbc = jdbc;
        this.socket = socket;
    }

    public record Level(long price, long quantity) {}
    public record TradeTick(long price, long quantity, Instant executedAt) {}
    public record MarketSnapshot(String symbol, long sequenceId, Instant publishedAt,
                                 Level bestBid, Level bestAsk, List<TradeTick> recentTrades) {}
    public record PrivateOrderUpdate(long id, String symbol, String status,
                                     long remainingQuantity, long sequenceId) {}

    /** The REST snapshot is the resynchronization path for delayed/lost STOMP data. */
    public MarketSnapshot snapshot(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9]{0,9}") ||
                !Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM instruments WHERE symbol=? AND active=TRUE)",
                        Boolean.class, symbol))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown instrument");
        }
        long sequence = jdbc.queryForObject(
                "SELECT last_completed_sequence_id FROM command_checkpoint WHERE symbol=?",
                Long.class, symbol);
        Level bid = bestLevel(symbol, "BUY", "DESC");
        Level ask = bestLevel(symbol, "SELL", "ASC");
        List<TradeTick> recent = jdbc.query(
                "SELECT t.execution_price, t.execution_quantity, t.executed_at " +
                "FROM trades t JOIN orders b ON b.order_id=t.buy_order_id " +
                "WHERE b.symbol=? ORDER BY t.trade_id DESC LIMIT 20",
                (rs, row) -> new TradeTick(rs.getLong(1), rs.getLong(2),
                        rs.getTimestamp(3).toInstant()), symbol);
        return new MarketSnapshot(symbol, sequence, Instant.now(), bid, ask, recent);
    }

    private Level bestLevel(String symbol, String side, String sortOrder) {
        // sortOrder is supplied ONLY by this class; never interpolated user input.
        String sql = "SELECT limit_price, SUM(remaining_quantity) " +
                "FROM orders WHERE symbol=? AND side=? AND remaining_quantity>0 " +
                "AND status IN ('OPEN','PARTIALLY_FILLED') " +
                "GROUP BY limit_price ORDER BY limit_price " + sortOrder + " LIMIT 1";
        List<Level> levels = jdbc.query(sql,
                (rs, row) -> new Level(rs.getLong(1), rs.getLong(2)), symbol, side);
        return levels.isEmpty() ? null : levels.getFirst();
    }

    @Scheduled(initialDelayString = "${exchangex.stream.poll-ms:400}",
            fixedDelayString = "${exchangex.stream.poll-ms:400}")
    public void broadcastChanges() {
        try {
            List<String> symbols = jdbc.queryForList(
                    "SELECT symbol FROM instruments WHERE active=TRUE ORDER BY symbol", String.class);
            for (String symbol : symbols) {
                Long checkpoint = jdbc.queryForObject(
                        "SELECT last_completed_sequence_id FROM command_checkpoint WHERE symbol=?",
                        Long.class, symbol);
                if (!checkpoint.equals(lastPublished.get(symbol))) {
                    MarketSnapshot current = snapshot(symbol);
                    socket.convertAndSend("/topic/market/" + symbol, current);
                    lastPublished.put(symbol, current.sequenceId());
                }
            }
            publishPrivateOrderChanges();
        } catch (RuntimeException e) {
            // Never bring down or block matching/settlement on a stream transport failure.
            log.warn("Market-data stream poll failed; clients should refresh REST snapshots", e);
        }
    }

    private void publishPrivateOrderChanges() {
        // Bounded scan for demo usage. At production scale use an outbox + broker.
        List<PrivateOrderRow> orders = jdbc.query(
                "SELECT o.client_order_id, o.symbol, o.execution_status, " +
                "o.remaining_quantity, o.exchange_sequence_id, own.user_id " +
                "FROM orders o JOIN order_ownership own ON own.order_id=o.client_order_id " +
                "JOIN exchange_users u ON u.id=own.user_id AND u.enabled=TRUE " +
                "ORDER BY o.exchange_sequence_id DESC LIMIT " + MAX_PRIVATE_ORDERS,
                (rs, row) -> new PrivateOrderRow(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)));
        Set<Long> seen = new HashSet<>();
        for (PrivateOrderRow order : orders) {
            seen.add(order.id());
            String fingerprint = order.status() + ":" + order.remainingQuantity() + ":" + order.sequenceId();
            if (!fingerprint.equals(lastPrivateState.get(order.id()))) {
                socket.convertAndSendToUser(String.valueOf(order.userId()), "/queue/orders",
                        new PrivateOrderUpdate(order.id(), order.symbol(), order.status(),
                                order.remainingQuantity(), order.sequenceId()));
                lastPrivateState.put(order.id(), fingerprint);
            }
        }
        lastPrivateState.keySet().retainAll(seen);
    }

    private record PrivateOrderRow(long id, String symbol, String status,
                                   long remainingQuantity, long sequenceId, long userId) {}
}
