package com.exchangex.api.repository;

import com.exchangex.engine.Side;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for exchange_commands table.
 * Handles durable command admission and status updates.
 */
@Repository
public interface CommandRepository {

    /**
     * Insert a new SUBMIT command. Returns the generated sequence_id.
     * Throws exception on duplicate client order_id (idempotency).
     */
    long insertSubmitCommand(long clientOrderId, String symbol, Side side, long price, long quantity);

    /**
     * Insert a new CANCEL command. Returns the generated sequence_id.
     */
    long insertCancelCommand(long clientOrderId, String symbol);

    /**
     * Find a pending SUBMIT command by client order_id (for idempotency check).
     */
    Optional<CommandRecord> findPendingSubmitByClientOrderId(long clientOrderId, String symbol);

    /**
     * Find a pending CANCEL command by client order_id.
     */
    Optional<CommandRecord> findPendingCancelByClientOrderId(long clientOrderId, String symbol);

    /**
     * Get all commands eligible for replay for a symbol since a given sequence_id (exclusive), ordered by sequence_id.
     * Includes both PENDING and FAILED commands, as both need to be replayed on recovery.
     * Used for replay and catch-up processing.
     */
    List<CommandRecord> findPendingCommandsSince(String symbol, long lastCompletedSequenceId);

    /** Number of commands not yet completed for this instrument (for admission backpressure). */
    long countOutstanding(String symbol);

    /**
     * Mark a command as COMPLETED with timestamp.
     */
    void markCompleted(long sequenceId);

    /**
     * Mark a command as FAILED with timestamp.
     */
    void markFailed(long sequenceId);

    /**
     * Find a command by sequence_id.
     */
    Optional<CommandRecord> findBySequenceId(long sequenceId);

    /**
     * Delete all commands (for test cleanup).
     */
    void deleteAll();

    /**
     * Command record for replay and processing.
     */
    record CommandRecord(
            long sequenceId,
            String commandType,  // "SUBMIT" or "CANCEL"
            long clientOrderId,
            String symbol,
            Side side,
            Long price,        // null for CANCEL
            Long quantity,     // null for CANCEL
            String status      // PENDING, COMPLETED, FAILED
    ) {}
}