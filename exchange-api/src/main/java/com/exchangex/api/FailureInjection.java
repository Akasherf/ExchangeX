package com.exchangex.api;

import org.springframework.lang.Nullable;

/**
 * Test-only failure injection seam for deterministic fault injection.
 * <p>
 * This interface is package-private and disabled by default (null implementation).
 * It is ONLY activated via test configuration in {@code postgres-integration} profile tests.
 * <p>
 * The callbacks are invoked at precisely defined locations inside the execution transaction
 * ({@link DurableCommandCoordinator#commitResults}) to exercise real Spring-managed
 * transaction boundaries and cause PostgreSQL rollback.
 * <p>
 * <b>Security:</b> No production configuration enables this. The implementation is
 * package-private and only test configuration provides a non-null instance.
 */
interface FailureInjection {

    /**
     * Called after all order upserts but before trade inserts.
     * Use to simulate failure during trade persistence.
     */
    void afterOrdersBeforeTrades(long sequenceId);

    /**
     * Called after trade inserts but before command status update.
     * Use to simulate failure during command completion.
     */
    void afterTradesBeforeCommandStatus(long sequenceId);

    /**
     * Called after command status update but before checkpoint advance.
     * Use to simulate failure during checkpoint persistence.
     */
    void afterCommandStatusBeforeCheckpoint(long sequenceId);

    /**
     * Default no-op implementation - used in production (injected as null, guarded by null checks).
     */
    class NoOp implements FailureInjection {
        @Override public void afterOrdersBeforeTrades(long sequenceId) {}
        @Override public void afterTradesBeforeCommandStatus(long sequenceId) {}
        @Override public void afterCommandStatusBeforeCheckpoint(long sequenceId) {}
    }
}