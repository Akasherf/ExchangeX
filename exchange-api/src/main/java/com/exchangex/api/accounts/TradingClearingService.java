package com.exchangex.api.accounts;

import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Cash/stock movements for a simulation exchange. Each method MUST run inside
 * the caller's admission or execution database transaction. No settlement is
 * performed independently of command status, order/trade writes and checkpoint.
 *
 * Price is exactly one integer cash unit per share/tick. No floating point,
 * margin, short selling or fees are supported.
 */
@Service
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class TradingClearingService {
    private final JdbcTemplate jdbc;
    private final AccountLedger accounts;

    @Value("${exchangex.trading.max-order-quantity:1000000}")
    private long maxQuantity;
    @Value("${exchangex.trading.max-order-notional:1000000000000}")
    private long maxNotional;

    public TradingClearingService(JdbcTemplate jdbc, AccountLedger accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    private record Reservation(long orderId, long userId, String symbol, Side side,
                               long limitPrice, long reservedRemaining) {}

    private static void transactional() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Financial mutation requires an active transaction");
        }
    }

    private static void positive(long v, String name) {
        if (v <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private Reservation reservation(long orderId) {
        List<Reservation> rows = jdbc.query("""
                SELECT order_id,user_id,symbol,side,limit_price,reserved_remaining
                FROM trading_order_reservations WHERE order_id = ? FOR UPDATE
                """, (rs, row) -> new Reservation(rs.getLong(1), rs.getLong(2), rs.getString(3),
                Side.valueOf(rs.getString(4)), rs.getLong(5), rs.getLong(6)), orderId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Unfunded or legacy order cannot be settled: " + orderId);
        }
        return rows.getFirst();
    }

    private void lockAccounts(long first, long second) {
        long low = Math.min(first, second);
        long high = Math.max(first, second);
        jdbc.queryForObject("SELECT user_id FROM trading_accounts WHERE user_id = ? FOR UPDATE", Long.class, low);
        if (low != high) {
            jdbc.queryForObject("SELECT user_id FROM trading_accounts WHERE user_id = ? FOR UPDATE", Long.class, high);
        }
    }

    /** Refuse activation if old active/pending orders lack paid-in reservations. */
    public void verifyActivation(String symbol) {
        Long outstanding = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM orders o
                        LEFT JOIN trading_order_reservations r ON r.order_id=o.order_id
                        WHERE o.symbol=? AND o.execution_status IN ('PENDING','PARTIAL') AND r.order_id IS NULL)
                       + (SELECT COUNT(*) FROM exchange_commands c
                          LEFT JOIN trading_order_reservations r ON r.order_id=c.order_id
                          WHERE c.symbol=? AND c.command_type='SUBMIT'
                            AND c.status IN ('PENDING','FAILED') AND r.order_id IS NULL)
                """, Long.class, symbol, symbol);
        if (outstanding != null && outstanding > 0) {
            throw new IllegalStateException("Cannot enable funded trading on " + symbol
                    + ": " + outstanding + " legacy/unreserved active or pending orders. Resolve through an audited migration first.");
        }
    }

    /** Called after journal and order_ownership are written, in the same admission TX. */
    public void reserveForSubmission(long orderId, long userId, String symbol, Side side,
                                     long price, long quantity) {
        transactional();
        positive(orderId, "orderId");
        positive(userId, "userId");
        positive(price, "price");
        positive(quantity, "quantity");
        if (quantity > maxQuantity) throw new IllegalArgumentException("Order quantity exceeds risk limit");
        long notional = Math.multiplyExact(price, quantity);
        if (notional > maxNotional) throw new IllegalArgumentException("Order notional exceeds risk limit");
        Boolean active = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM instruments WHERE symbol=? AND active=TRUE)",
                Boolean.class, symbol);
        if (!Boolean.TRUE.equals(active)) throw new IllegalArgumentException("Inactive instrument");
        if (side == Side.BUY) {
            accounts.applyCash(userId, "order:" + orderId + ":reserve", "RESERVE", -notional, notional);
        } else if (side == Side.SELL) {
            accounts.applyHolding(userId, symbol, "order:" + orderId + ":reserve", "RESERVE", -quantity, quantity);
        } else {
            throw new IllegalArgumentException("Unsupported side");
        }
        jdbc.update("""
                INSERT INTO trading_order_reservations
                (order_id,user_id,symbol,side,limit_price,reserved_remaining)
                VALUES (?,?,?,?,?,?)
                """, orderId, userId, symbol, side.name(), price,
                side == Side.BUY ? notional : quantity);
    }

    /** Called once per committed match inside commitResults' existing transaction. */
    public void settleTrade(long commandSequenceId, int index, String symbol, Trade trade) {
        transactional();
        positive(trade.quantity(), "trade.quantity");
        positive(trade.price(), "trade.price");
        // First read identity without taking a reservation lock; account locks
        // always come first, consistently with admission and other settlements.
        Long buyer = jdbc.queryForObject("SELECT user_id FROM trading_order_reservations WHERE order_id=?",
                Long.class, trade.buyOrderId());
        Long seller = jdbc.queryForObject("SELECT user_id FROM trading_order_reservations WHERE order_id=?",
                Long.class, trade.sellOrderId());
        if (buyer == null || seller == null) throw new IllegalStateException("Unfunded counterparty");
        lockAccounts(buyer, seller);
        Reservation buy = reservation(trade.buyOrderId());
        Reservation sell = reservation(trade.sellOrderId());
        if (buy.side() != Side.BUY || sell.side() != Side.SELL ||
                !buy.symbol().equals(symbol) || !sell.symbol().equals(symbol) ||
                buy.userId() != buyer || sell.userId() != seller) {
            throw new IllegalStateException("Settlement order/owner mismatch");
        }
        if (trade.price() > buy.limitPrice() || trade.price() < sell.limitPrice()) {
            throw new IllegalStateException("Execution price outside limit");
        }
        long reservedSpent = Math.multiplyExact(buy.limitPrice(), trade.quantity());
        long paid = Math.multiplyExact(trade.price(), trade.quantity());
        long refund = Math.subtractExact(reservedSpent, paid);
        if (buy.reservedRemaining() < reservedSpent || sell.reservedRemaining() < trade.quantity()) {
            throw new IllegalStateException("Unfunded or duplicate fill");
        }
        String key = "trade:" + commandSequenceId + ":" + index;
        accounts.applyCash(buyer, key + ":buyer", "SETTLEMENT", refund, -reservedSpent);
        accounts.applyCash(seller, key + ":seller", "TRADE_CREDIT", paid, 0);
        accounts.applyHolding(buyer, symbol, key + ":buyer", "TRADE_CREDIT", trade.quantity(), 0);
        accounts.applyHolding(seller, symbol, key + ":seller", "SETTLEMENT", 0, -trade.quantity());
        jdbc.update("UPDATE trading_order_reservations SET reserved_remaining=? WHERE order_id=?",
                Math.subtractExact(buy.reservedRemaining(), reservedSpent), buy.orderId());
        jdbc.update("UPDATE trading_order_reservations SET reserved_remaining=? WHERE order_id=?",
                Math.subtractExact(sell.reservedRemaining(), trade.quantity()), sell.orderId());
    }

    /** Return unfilled BUY cash or SELL stock only after a successful CANCEL command. */
    public void releaseCancellation(long orderId) {
        transactional();
        Long user = jdbc.queryForObject("SELECT user_id FROM trading_order_reservations WHERE order_id=?",
                Long.class, orderId);
        if (user == null) throw new IllegalStateException("Missing cancellation reservation");
        lockAccounts(user, user);
        Reservation reserve = reservation(orderId);
        if (reserve.reservedRemaining() == 0) return;
        if (reserve.side() == Side.BUY) {
            accounts.applyCash(user, "order:" + orderId + ":cancel", "RELEASE",
                    reserve.reservedRemaining(), -reserve.reservedRemaining());
        } else {
            accounts.applyHolding(user, reserve.symbol(), "order:" + orderId + ":cancel", "RELEASE",
                    reserve.reservedRemaining(), -reserve.reservedRemaining());
        }
        jdbc.update("UPDATE trading_order_reservations SET reserved_remaining=0 WHERE order_id=?", orderId);
    }
}
