package com.exchangex.api.observability;

import com.exchangex.api.CommandLifecycleObserver;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Process-local counters/timers from actual coordinator events plus DB-backed
 * durable-state gauges. Restart resets counters, not database gauges.
 */
@Component
public final class ExchangeMetrics implements CommandLifecycleObserver {
    private final Counter apiRequestsTotal;
    private final Counter apiErrorsTotal;
    private final Timer apiRequestLatency;
    private final Counter commandsAdmittedTotal;
    private final Counter commandsCompletedTotal;
    private final Counter commandsFailedTotal;
    private final Counter commandsReplayedTotal;
    private final Counter duplicateAdmissionsTotal;
    private final Counter ordersSubmittedTotal;
    private final Counter ordersCancelledTotal;
    private final Counter tradesExecutedTotal;
    private final Timer admissionLatency;
    private final Timer processingLatency;
    private final Timer commitLatency;
    private final AtomicLong coordinatorRunning = new AtomicLong();

    public ExchangeMetrics(MeterRegistry registry,
                           CommandRepository commands,
                           CheckpointRepository checkpoints,
                           @Value("${spring.application.name:exchange-api}") String app) {
        List<Tag> tags = List.of(Tag.of("application", app), Tag.of("symbol", "AAPL"));
        apiRequestsTotal = counter(registry, tags, "exchange.api.requests.total", "API requests seen by MVC interceptor");
        apiErrorsTotal = counter(registry, tags, "exchange.api.errors.total", "API responses >= 400");
        apiRequestLatency = timer(registry, tags, "exchange.api.request.latency");
        commandsAdmittedTotal = counter(registry, tags, "exchange.commands.admitted.total", "New durable journal commands admitted");
        commandsCompletedTotal = counter(registry, tags, "exchange.commands.completed.total", "Successfully committed live commands");
        commandsFailedTotal = counter(registry, tags, "exchange.commands.failed.total", "Failed execution or commit attempts");
        commandsReplayedTotal = counter(registry, tags, "exchange.commands.replayed.total", "Successfully replayed and committed commands");
        duplicateAdmissionsTotal = counter(registry, tags, "exchange.commands.duplicate.total", "Idempotent retries, not new commands");
        ordersSubmittedTotal = counter(registry, tags, "exchange.orders.submitted.total", "New SUBMIT journal admissions");
        ordersCancelledTotal = counter(registry, tags, "exchange.orders.cancelled.total", "New CANCEL journal admissions (not guaranteed cancelled fills)");
        tradesExecutedTotal = counter(registry, tags, "exchange.trades.executed.total", "Committed trades (including recovered commands)");
        admissionLatency = timer(registry, tags, "exchange.commands.admission.latency");
        processingLatency = timer(registry, tags, "exchange.commands.processing.latency");
        commitLatency = timer(registry, tags, "exchange.commands.commit.latency");

        Gauge.builder("exchange.backlog.current", () -> safeRead(() -> commands.countOutstanding("AAPL")))
                .description("Durable PENDING + FAILED commands in PostgreSQL").tags(tags).register(registry);
        Gauge.builder("exchange.commands.pending", () -> safeRead(() -> commands.countOutstanding("AAPL")))
                .description("Outstanding journal commands including FAILED").tags(tags).register(registry);
        Gauge.builder("exchange.checkpoint.last_sequence", () -> safeRead(() -> checkpoints.getLastCompletedSequenceId("AAPL")))
                .description("Last committed checkpoint from PostgreSQL").tags(tags).register(registry);
        Gauge.builder("exchange.coordinator.active", coordinatorRunning, AtomicLong::get)
                .description("Coordinator has started and has not reported halt (single process)")
                .tags(tags).register(registry);
    }

    private static Counter counter(MeterRegistry registry, Iterable<Tag> tags, String name, String description) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    private static Timer timer(MeterRegistry registry, Iterable<Tag> tags, String name) {
        return Timer.builder(name).tags(tags).publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram().register(registry);
    }

    private static double safeRead(LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException databaseFailure) {
            // NaN communicates that the durable state is currently unobservable.
            return Double.NaN;
        }
    }

    public void recordApiRequest(String endpoint, int statusCode, Duration latency) {
        apiRequestsTotal.increment();
        apiRequestLatency.record(latency);
        if (statusCode >= 400) apiErrorsTotal.increment();
    }

    @Override
    public void commandAdmitted(String type, boolean duplicate, long nanos) {
        admissionLatency.record(nanos, TimeUnit.NANOSECONDS);
        if (duplicate) {
            duplicateAdmissionsTotal.increment();
            return;
        }
        commandsAdmittedTotal.increment();
        if ("SUBMIT".equals(type)) ordersSubmittedTotal.increment();
        if ("CANCEL".equals(type)) ordersCancelledTotal.increment();
    }

    @Override
    public void commandCommitted(String type, boolean replay, long executionNanos, long commitNanos, int trades) {
        if (replay) commandsReplayedTotal.increment();
        else commandsCompletedTotal.increment();
        processingLatency.record(executionNanos, TimeUnit.NANOSECONDS);
        commitLatency.record(commitNanos, TimeUnit.NANOSECONDS);
        if (trades > 0) tradesExecutedTotal.increment(trades);
    }

    @Override
    public void commandFailed(boolean replay) {
        commandsFailedTotal.increment();
    }

    @Override
    public void coordinatorRunning(boolean active) {
        coordinatorRunning.set(active ? 1 : 0);
    }
}
