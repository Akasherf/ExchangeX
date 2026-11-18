package com.exchangex.api.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observes committed PostgreSQL projections only. No locks, coordinator calls,
 * trading-path callbacks, ledger writes, or mutable instrument tag expansion.
 * The three known simulation instruments intentionally bound metric cardinality.
 */
@Component
@Profile("!test & !postgres-integration")
@ConditionalOnProperty(name = "exchangex.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class InstrumentMetricsService {
    private static final Logger log = LoggerFactory.getLogger(InstrumentMetricsService.class);
    private static final List<String> SYMBOLS = List.of("AAPL", "GOOGL", "MSFT");
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> checkpoint = new HashMap<>();
    private final Map<String, AtomicLong> trades = new HashMap<>();
    private final Map<String, AtomicLong> openOrders = new HashMap<>();

    public InstrumentMetricsService(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String symbol : SYMBOLS) {
            AtomicLong checkpointValue = new AtomicLong();
            AtomicLong tradeValue = new AtomicLong();
            AtomicLong orderValue = new AtomicLong();
            checkpoint.put(symbol, checkpointValue);
            trades.put(symbol, tradeValue);
            openOrders.put(symbol, orderValue);
            Gauge.builder("exchangex.instrument.checkpoint.sequence", checkpointValue, AtomicLong::doubleValue)
                    .tag("symbol", symbol).description("Last committed command checkpoint sequence").register(registry);
            Gauge.builder("exchangex.instrument.trades.committed", tradeValue, AtomicLong::doubleValue)
                    .tag("symbol", symbol).description("Current count of committed trades").register(registry);
            Gauge.builder("exchangex.instrument.orders.open", orderValue, AtomicLong::doubleValue)
                    .tag("symbol", symbol).description("Current open and partially filled orders").register(registry);
        }
    }

    @Scheduled(initialDelayString = "${exchangex.metrics.poll-ms:15000}",
            fixedDelayString = "${exchangex.metrics.poll-ms:15000}")
    public void refresh() {
        for (String symbol : SYMBOLS) {
            try {
                Long seq = jdbc.queryForObject(
                        "SELECT last_completed_sequence_id FROM command_checkpoint WHERE symbol=?",
                        Long.class, symbol);
                Long count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM trades t JOIN orders b ON b.order_id=t.buy_order_id WHERE b.symbol=?",
                        Long.class, symbol);
                Long resting = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM orders WHERE symbol=? AND remaining_quantity>0 " +
                                "AND status IN ('OPEN','PARTIALLY_FILLED')", Long.class, symbol);
                if (seq != null && count != null && resting != null) {
                    checkpoint.get(symbol).set(seq);
                    trades.get(symbol).set(count);
                    openOrders.get(symbol).set(resting);
                }
            } catch (RuntimeException e) {
                // Monitoring must never change or fail the matching or settlement path.
                log.warn("Instrument metrics refresh failed for {} (previous sample retained)", symbol, e);
            }
        }
    }
}
