package com.exchangex.api.accounts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Durable, integer-only simulation account ledger (Sprint 4.2A).
 * Not connected to order admission or settlement until Sprint 4.2B.
 *
 * Each mutation locks the account row before checking an event key. This
 * serializes updates from competing requests and guarantees no lost balance
 * updates on PostgreSQL. Both the snapshot update and journal insertion
 * participate in one JDBC transaction.
 *
 * Never expose simulation credit/grant methods through an HTTP controller.
 */
@Repository
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class AccountLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AccountLedger(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record CashBalance(long available, long reserved) {
        public long total() { return Math.addExact(available, reserved); }
    }

    public record Holding(String symbol, long available, long reserved) {
        public long total() { return Math.addExact(available, reserved); }
    }

    public void openAccount(long userId) {
        positive(userId, "userId");
        jdbc.update("INSERT INTO trading_accounts (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
    }

    public CashBalance cashBalance(long userId) {
        positive(userId, "userId");
        return jdbc.queryForObject(
                "SELECT available_cash, reserved_cash FROM trading_accounts WHERE user_id = ?",
                (rs, row) -> new CashBalance(rs.getLong(1), rs.getLong(2)), userId);
    }

    public List<Holding> holdings(long userId) {
        positive(userId, "userId");
        return jdbc.query("SELECT symbol, available_quantity, reserved_quantity " +
                        "FROM account_holdings WHERE user_id = ? ORDER BY symbol",
                (rs, row) -> new Holding(rs.getString(1), rs.getLong(2), rs.getLong(3)), userId);
    }

    /*
     * Simulation/test-seeding operations are package-private on purpose.
     * Funding, matching and settlement will be implemented as explicit,
     * audited entry points in Sprint 4.2B; no public deposit endpoint exists.
     */
    void creditSimulation(long userId, long amount, String eventKey) {
        positive(amount, "amount");
        changeCash(userId, eventKey, "SIM_CREDIT", amount, 0);
    }

    void reserveCash(long userId, long amount, String eventKey) {
        positive(amount, "amount");
        changeCash(userId, eventKey, "RESERVE", Math.negateExact(amount), amount);
    }

    void releaseCash(long userId, long amount, String eventKey) {
        positive(amount, "amount");
        changeCash(userId, eventKey, "RELEASE", amount, Math.negateExact(amount));
    }

    void grantSimulationHolding(long userId, String symbol, long quantity, String eventKey) {
        positive(quantity, "quantity");
        changeHolding(userId, symbol, eventKey, "SIM_GRANT", quantity, 0);
    }

    void reserveHolding(long userId, String symbol, long quantity, String eventKey) {
        positive(quantity, "quantity");
        changeHolding(userId, symbol, eventKey, "RESERVE", Math.negateExact(quantity), quantity);
    }

    void releaseHolding(long userId, String symbol, long quantity, String eventKey) {
        positive(quantity, "quantity");
        changeHolding(userId, symbol, eventKey, "RELEASE", quantity, Math.negateExact(quantity));
    }

    // Package-scoped primitives used by the clearing service inside its surrounding
    // admission/execution transaction; the ledger still enforces nonnegative totals
    // and globally unique idempotency keys per account.
    void applyCash(long userId, String key, String type, long availableDelta, long reservedDelta) {
        changeCash(userId, key, type, availableDelta, reservedDelta);
    }

    void applyHolding(long userId, String symbol, String key, String type,
                      long availableDelta, long reservedDelta) {
        changeHolding(userId, symbol, key, type, availableDelta, reservedDelta);
    }

    private void changeCash(long userId, String key, String type, long availableDelta, long reservedDelta) {
        positive(userId, "userId");
        validKey(key);
        tx.executeWithoutResult(status -> {
            CashBalance old = jdbc.queryForObject(
                    "SELECT available_cash, reserved_cash FROM trading_accounts WHERE user_id = ? FOR UPDATE",
                    (rs, row) -> new CashBalance(rs.getLong(1), rs.getLong(2)), userId);
            var prior = jdbc.query("SELECT event_type, available_delta, reserved_delta " +
                            "FROM cash_ledger_events WHERE user_id = ? AND event_key = ?",
                    (rs, row) -> new Event(rs.getString(1), rs.getLong(2), rs.getLong(3)), userId, key);
            if (!prior.isEmpty()) {
                verifySameEvent(prior.getFirst(), new Event(type, availableDelta, reservedDelta));
                return;
            }
            CashBalance next = new CashBalance(Math.addExact(old.available(), availableDelta),
                    Math.addExact(old.reserved(), reservedDelta));
            if (next.available() < 0 || next.reserved() < 0) {
                throw new InsufficientBalanceException("Insufficient available or reserved cash");
            }
            next.total(); // Catch overflow of aggregate balance before persisting.
            jdbc.update("UPDATE trading_accounts SET available_cash = ?, reserved_cash = ?, updated_at = NOW() " +
                    "WHERE user_id = ?", next.available(), next.reserved(), userId);
            jdbc.update("INSERT INTO cash_ledger_events (user_id, event_key, event_type, " +
                            "available_delta, reserved_delta) VALUES (?, ?, ?, ?, ?)",
                    userId, key, type, availableDelta, reservedDelta);
        });
    }

    private void changeHolding(long userId, String symbol, String key, String type,
                               long availableDelta, long reservedDelta) {
        positive(userId, "userId");
        validKey(key);
        if (symbol == null || !symbol.matches("[A-Z0-9]{1,10}")) {
            throw new IllegalArgumentException("Invalid instrument symbol");
        }
        tx.executeWithoutResult(status -> {
            // Lock account first, consistently with future cash+position settlement.
            jdbc.queryForObject("SELECT user_id FROM trading_accounts WHERE user_id = ? FOR UPDATE",
                    Long.class, userId);
            jdbc.update("INSERT INTO account_holdings (user_id, symbol) VALUES (?, ?) " +
                    "ON CONFLICT (user_id, symbol) DO NOTHING", userId, symbol);
            Holding old = jdbc.queryForObject(
                    "SELECT symbol, available_quantity, reserved_quantity FROM account_holdings " +
                            "WHERE user_id = ? AND symbol = ? FOR UPDATE",
                    (rs, row) -> new Holding(rs.getString(1), rs.getLong(2), rs.getLong(3)), userId, symbol);
            var prior = jdbc.query("SELECT event_type, available_delta, reserved_delta " +
                            "FROM holding_ledger_events WHERE user_id = ? AND symbol = ? AND event_key = ?",
                    (rs, row) -> new Event(rs.getString(1), rs.getLong(2), rs.getLong(3)), userId, symbol, key);
            if (!prior.isEmpty()) {
                verifySameEvent(prior.getFirst(), new Event(type, availableDelta, reservedDelta));
                return;
            }
            Holding next = new Holding(symbol, Math.addExact(old.available(), availableDelta),
                    Math.addExact(old.reserved(), reservedDelta));
            if (next.available() < 0 || next.reserved() < 0) {
                throw new InsufficientBalanceException("Insufficient available or reserved holdings");
            }
            next.total(); // Catch overflow of aggregate position before persisting.
            jdbc.update("UPDATE account_holdings SET available_quantity = ?, reserved_quantity = ?, " +
                    "updated_at = NOW() WHERE user_id = ? AND symbol = ?",
                    next.available(), next.reserved(), userId, symbol);
            jdbc.update("INSERT INTO holding_ledger_events (user_id, symbol, event_key, event_type, " +
                            "available_delta, reserved_delta) VALUES (?, ?, ?, ?, ?, ?)",
                    userId, symbol, key, type, availableDelta, reservedDelta);
        });
    }

    private record Event(String type, long availableDelta, long reservedDelta) {}

    private static void verifySameEvent(Event previous, Event incoming) {
        if (!Objects.equals(previous, incoming)) {
            throw new IllegalStateException("Idempotency key already used for a different event");
        }
    }

    private static void positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void validKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("eventKey must be 1 to 128 characters");
        }
    }

    public static final class InsufficientBalanceException extends IllegalStateException {
        public InsufficientBalanceException(String message) { super(message); }
    }
}
