package com.exchangex.api;

/**
 * Best-effort, process-local operational events emitted only after durable
 * transaction outcomes are known. Implementations must not mutate journal state.
 */
public interface CommandLifecycleObserver {
    CommandLifecycleObserver NOOP = new CommandLifecycleObserver() {};

    default void commandAdmitted(String type, boolean duplicate, long admissionNanos) {}
    default void commandCommitted(String type, boolean replay, long executionNanos,
                                  long commitNanos, int trades) {}
    default void commandFailed(boolean replay) {}
    default void coordinatorRunning(boolean active) {}
}
