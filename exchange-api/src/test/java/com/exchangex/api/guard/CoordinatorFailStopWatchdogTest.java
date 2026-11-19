package com.exchangex.api.guard;

import com.exchangex.api.SymbolCoordinatorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoordinatorFailStopWatchdogTest {

    @Test
    void dockerProfileSpringContextWiresProductionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("docker");
            context.registerBean(WriterLease.class, () -> mock(WriterLease.class));
            context.registerBean(SymbolCoordinatorRegistry.class,
                    () -> mock(SymbolCoordinatorRegistry.class));
            context.register(CoordinatorFailStopWatchdog.class);
            context.refresh();
            assertNotNull(context.getBean(CoordinatorFailStopWatchdog.class));
        }
    }

    @Test
    void healthyLeaseAndWorkersDoNotTriggerExit() {
        WriterLease lease = mock(WriterLease.class);
        SymbolCoordinatorRegistry registry = mock(SymbolCoordinatorRegistry.class);
        when(lease.healthy()).thenReturn(true);
        when(registry.allWorkersRunning()).thenReturn(true);
        AtomicInteger exits = new AtomicInteger();
        var watchdog = new CoordinatorFailStopWatchdog(registry, lease, exits::incrementAndGet);
        try {
            watchdog.checkOnce();
            assertEquals(0, exits.get());
        } finally {
            watchdog.close();
        }
    }

    @Test
    void lostLeaseTriggersFailStop() {
        WriterLease lease = mock(WriterLease.class);
        SymbolCoordinatorRegistry registry = mock(SymbolCoordinatorRegistry.class);
        when(lease.healthy()).thenReturn(false);
        AtomicInteger exits = new AtomicInteger();
        var watchdog = new CoordinatorFailStopWatchdog(registry, lease, exits::incrementAndGet);
        try {
            watchdog.checkOnce();
            assertEquals(1, exits.get());
            verify(registry, never()).allWorkersRunning();
        } finally {
            watchdog.close();
        }
    }

    @Test
    void stoppedWorkerTriggersFailStopEvenIfDatabaseLeaseIsHealthy() {
        WriterLease lease = mock(WriterLease.class);
        SymbolCoordinatorRegistry registry = mock(SymbolCoordinatorRegistry.class);
        when(lease.healthy()).thenReturn(true);
        when(registry.allWorkersRunning()).thenReturn(false);
        AtomicInteger exits = new AtomicInteger();
        var watchdog = new CoordinatorFailStopWatchdog(registry, lease, exits::incrementAndGet);
        try {
            watchdog.checkOnce();
            assertEquals(1, exits.get());
        } finally {
            watchdog.close();
        }
    }

    @Test
    void failedReadinessInspectionFailsClosed() {
        WriterLease lease = mock(WriterLease.class);
        SymbolCoordinatorRegistry registry = mock(SymbolCoordinatorRegistry.class);
        when(lease.healthy()).thenThrow(new IllegalStateException("disconnected"));
        AtomicInteger exits = new AtomicInteger();
        var watchdog = new CoordinatorFailStopWatchdog(registry, lease, exits::incrementAndGet);
        try {
            watchdog.checkOnce();
            assertEquals(1, exits.get());
        } finally {
            watchdog.close();
        }
    }
}
