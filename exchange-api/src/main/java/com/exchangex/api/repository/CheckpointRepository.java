package com.exchangex.api.repository;

import org.springframework.stereotype.Repository;

/**
 * Repository for command_checkpoint table - tracks last committed sequence per symbol.
 */
@Repository
public interface CheckpointRepository {

    /**
     * Get the last completed sequence_id for a symbol.
     * Returns 0 if no checkpoint exists.
     */
    long getLastCompletedSequenceId(String symbol);

    /**
     * Atomically advance the checkpoint to a new sequence_id.
     * Only advances if the new value is greater than current.
     * Returns true if advanced, false if already at or past the target.
     */
    boolean advanceIfGreater(String symbol, long newSequenceId);

    /**
     * Reset checkpoint to 0 (for test cleanup).
     */
    void reset();
}