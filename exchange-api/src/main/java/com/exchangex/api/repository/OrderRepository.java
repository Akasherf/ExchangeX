package com.exchangex.api.repository;

import com.exchangex.engine.Side;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for orders table - durable order state.
 */
@Repository
public interface OrderRepository {

    /**
     * Insert a new order (from SUBMIT command execution).
     * Called when a SUBMIT command is first executed.
     */
    void insertOrder(OrderRecord order);

    /**
     * Update order remaining quantity and execution status after a fill.
     */
    void updateOrderExecution(long clientOrderId, long remainingQuantity, String executionStatus);

    /**
     * Mark order as cancelled.
     */
    void markCancelled(long clientOrderId);

    /**
     * Find order by client order_id (for idempotency and GET /orders/{id}).
     */
    Optional<OrderRecord> findByClientOrderId(long clientOrderId);

    /**
     * Find order by exchange sequence_id (for replay verification).
     */
    Optional<OrderRecord> findByExchangeSequenceId(long sequenceId);

    /**
     * Get all active orders for a symbol (for engine rebuild).
     */
    List<OrderRecord> findActiveOrders(String symbol);

    /**
     * Delete all orders (for test cleanup).
     */
    void deleteAll();

    /**
     * Order record for persistence and rebuild.
     */
    record OrderRecord(
            long orderId,              // PK, same as client_order_id for SUBMIT
            long clientOrderId,        // client-provided ID
            long exchangeSequenceId,   // sequence_id of command that created/modified this order
            String symbol,
            Side side,
            long limitPrice,
            long originalQuantity,
            long remainingQuantity,
            String executionStatus,    // PENDING, PARTIAL, FILLED, CANCELLED, REJECTED
            String status              // OPEN, PARTIALLY_FILLED, FILLED, CANCELLED (legacy)
    ) {}
}