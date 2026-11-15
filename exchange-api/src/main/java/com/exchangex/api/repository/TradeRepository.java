package com.exchangex.api.repository;

import com.exchangex.engine.Trade;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for trades table - durable trade execution records.
 */
@Repository
public interface TradeRepository {

    /**
     * Insert multiple trades atomically (single batch).
     * Each trade knows which command sequence produced it (buy_sequence_id, sell_sequence_id).
     */
    void insertTrades(List<TradeRecord> trades);

    /**
     * Get all trades for a symbol (for rebuild and trade history).
     */
    List<TradeRecord> findBySymbol(String symbol);

    /**
     * Delete all trades (for test cleanup).
     */
    void deleteAll();

    /**
     * Trade record with command sequence references for replay verification.
     */
    record TradeRecord(
            long tradeId,
            long buyOrderId,
            long sellOrderId,
            long executionPrice,
            long executionQuantity,
            long buySequenceId,   // sequence_id of BUY command that generated this trade
            long sellSequenceId   // sequence_id of SELL command that generated this trade
    ) {}

    /**
     * Convert from MatchingEngine Trade to persistence record.
     * Requires knowing the sequence_ids of the commanding orders.
     */
    static TradeRecord fromTrade(Trade trade, long buySequenceId, long sellSequenceId) {
        return new TradeRecord(
                0, // tradeId is IDENTITY
                trade.buyOrderId(),
                trade.sellOrderId(),
                trade.price(),
                trade.quantity(),
                buySequenceId,
                sellSequenceId
        );
    }
}