package com.exchangex.api.guard;

import com.exchangex.api.SymbolCoordinatorRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only process supervisor. A lost PostgreSQL session lock or stopped
 * matching worker is a terminal condition for this JVM. A fresh JVM can safely
 * acquire a NEW fenced writer generation and replay the durable journal.
 *
 * Do not attempt in-process re-acquisition: other symbol workers or admissions
 * could still be using the previous generation.
 */
@Component
@Profile("docker")
@ConditionalOnProperty(name = "exchangex.recovery.fail-stop.enabled", havingValue = "true", matchIfMissing = true)
public final class CoordinatorFailStopWatchdog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorFailStopWatchdog.class);
    private final SymbolCoordinatorRegistry registry;
    private final WriterLease lease;
    private final Runnable terminateProcess;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "exchangex-coordinator-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    public CoordinatorFailStopWatchdog(SymbolCoordinatorRegistry registry, WriterLease lease) {
        this(registry, lease, () -> System.exit(42));
    }

    // Injectable fail-stop callback avoids ever terminating a JUnit process.
    CoordinatorFailStopWatchdog(SymbolCoordinatorRegistry registry, WriterLease lease,
                               Runnable terminateProcess) {
        this.registry = registry;
        this.lease = lease;
        this.terminateProcess = terminateProcess;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(this::checkOnce, 2, 2, TimeUnit.SECONDS);
    }

    void checkOnce() {
        try {
            if (lease.healthy() && registry.allWorkersRunning()) return;
            log.error("ExchangeX writer lease or matching worker stopped. Exiting to let Docker " +
                    "restart a single fenced writer and replay committed journal state.");
        } catch (RuntimeException inspectionFailure) {
            log.error("Cannot establish safe matching readiness; failing closed", inspectionFailure);
        }
        terminateProcess.run();
    }

    @Override
    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }
}
