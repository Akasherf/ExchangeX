package com.exchangex.api.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstrumentMetricsServiceTest {
    @Test
    void publishesBoundedInstrumentGaugesFromCommittedQueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        for (String symbol : new String[]{"AAPL", "GOOGL", "MSFT"}) {
            when(jdbc.queryForObject(
                    "SELECT last_completed_sequence_id FROM command_checkpoint WHERE symbol=?",
                    Long.class, symbol)).thenReturn(21L);
            when(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trades t JOIN orders b ON b.order_id=t.buy_order_id WHERE b.symbol=?",
                    Long.class, symbol)).thenReturn(4L);
            when(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE symbol=? AND remaining_quantity>0 " +
                            "AND status IN ('OPEN','PARTIALLY_FILLED')", Long.class, symbol)).thenReturn(2L);
        }
        new InstrumentMetricsService(jdbc, registry).refresh();
        for (String symbol : new String[]{"AAPL", "GOOGL", "MSFT"}) {
            assertEquals(21.0, registry.get("exchangex.instrument.checkpoint.sequence")
                    .tag("symbol", symbol).gauge().value());
            assertEquals(4.0, registry.get("exchangex.instrument.trades.committed")
                    .tag("symbol", symbol).gauge().value());
            assertEquals(2.0, registry.get("exchangex.instrument.orders.open")
                    .tag("symbol", symbol).gauge().value());
        }
    }
}
