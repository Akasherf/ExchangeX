package com.exchangex.api.observability;

import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeMetricsTest {
    @Test
    void measuresRealEventsWithoutFabricatingBacklog() {
        CommandRepository commands = mock(CommandRepository.class);
        CheckpointRepository checkpoints = mock(CheckpointRepository.class);
        when(commands.countOutstanding("AAPL")).thenReturn(3L);
        when(checkpoints.getLastCompletedSequenceId("AAPL")).thenReturn(42L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExchangeMetrics metrics = new ExchangeMetrics(registry, commands, checkpoints, "exchange-api");
        assertEquals(3.0, registry.get("exchange.backlog.current").gauge().value());
        assertEquals(42.0, registry.get("exchange.checkpoint.last_sequence").gauge().value());
        assertEquals(0.0, registry.get("exchange.coordinator.active").gauge().value());
        metrics.coordinatorRunning(true);
        assertEquals(1.0, registry.get("exchange.coordinator.active").gauge().value());
        metrics.commandAdmitted("SUBMIT", false, 1_000_000);
        metrics.commandAdmitted("SUBMIT", true, 1_000_000);
        metrics.commandAdmitted("CANCEL", false, 1_000_000);
        metrics.commandCommitted("SUBMIT", false, 2_000_000, 3_000_000, 2);
        metrics.commandCommitted("SUBMIT", true, 2_000_000, 3_000_000, 1);
        metrics.commandFailed(false);
        metrics.recordApiRequest("/api/v1/orders", 503, Duration.ofMillis(15));

        assertEquals(2.0, registry.get("exchange.commands.admitted.total").counter().count());
        assertEquals(1.0, registry.get("exchange.commands.duplicate.total").counter().count());
        assertEquals(1.0, registry.get("exchange.commands.completed.total").counter().count());
        assertEquals(1.0, registry.get("exchange.commands.replayed.total").counter().count());
        assertEquals(1.0, registry.get("exchange.commands.failed.total").counter().count());
        assertEquals(3.0, registry.get("exchange.trades.executed.total").counter().count());
        assertEquals(1.0, registry.get("exchange.orders.cancelled.total").counter().count());
        assertEquals(1.0, registry.get("exchange.api.requests.total").counter().count());
        assertEquals(1.0, registry.get("exchange.api.errors.total").counter().count());
        metrics.coordinatorRunning(false);
        assertEquals(0.0, registry.get("exchange.coordinator.active").gauge().value());
    }
}
