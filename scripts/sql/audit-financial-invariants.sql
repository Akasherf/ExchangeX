-- ExchangeX Sprint 7 — read-only integrity audit on a consistent committed snapshot.
-- NEVER changes trading, ledger, or order data. Requires PostgreSQL 17.
-- A discrepancy raises an exception and makes psql exit nonzero with ON_ERROR_STOP=1.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

DO $audit$
BEGIN
    -- Each account cash snapshot must equal the sum of its journal events.
    IF EXISTS (
        SELECT 1 FROM trading_accounts a
        LEFT JOIN (
            SELECT user_id, SUM(available_delta::numeric) AS available,
                   SUM(reserved_delta::numeric) AS reserved
            FROM cash_ledger_events GROUP BY user_id
        ) events ON events.user_id = a.user_id
        WHERE a.available_cash::numeric <> COALESCE(events.available, 0)
           OR a.reserved_cash::numeric <> COALESCE(events.reserved, 0)
    ) THEN
        RAISE EXCEPTION 'AUDIT FAILED: cash snapshots disagree with cash ledger events';
    END IF;

    -- Each holding snapshot must equal the sum of its own journal events.
    IF EXISTS (
        SELECT 1 FROM account_holdings h
        LEFT JOIN (
            SELECT user_id, symbol, SUM(available_delta::numeric) AS available,
                   SUM(reserved_delta::numeric) AS reserved
            FROM holding_ledger_events GROUP BY user_id, symbol
        ) events ON events.user_id = h.user_id AND events.symbol = h.symbol
        WHERE h.available_quantity::numeric <> COALESCE(events.available, 0)
           OR h.reserved_quantity::numeric <> COALESCE(events.reserved, 0)
    ) THEN
        RAISE EXCEPTION 'AUDIT FAILED: holding snapshots disagree with holding ledger events';
    END IF;

    -- Pending BUY funds must be backed exactly by cash reserved in the accounts.
    IF EXISTS (
        SELECT 1 FROM trading_accounts a
        LEFT JOIN (
            SELECT user_id, SUM(reserved_remaining::numeric) AS reserved
            FROM trading_order_reservations WHERE side = 'BUY' GROUP BY user_id
        ) r ON r.user_id = a.user_id
        WHERE a.reserved_cash::numeric <> COALESCE(r.reserved, 0)
    ) THEN
        RAISE EXCEPTION 'AUDIT FAILED: BUY order reservations disagree with reserved cash';
    END IF;

    -- Pending SELL shares must be backed exactly by reserved account holdings.
    IF EXISTS (
        SELECT 1 FROM account_holdings h
        LEFT JOIN (
            SELECT user_id, symbol, SUM(reserved_remaining::numeric) AS reserved
            FROM trading_order_reservations WHERE side = 'SELL' GROUP BY user_id, symbol
        ) r ON r.user_id = h.user_id AND r.symbol = h.symbol
        WHERE h.reserved_quantity::numeric <> COALESCE(r.reserved, 0)
    ) THEN
        RAISE EXCEPTION 'AUDIT FAILED: SELL order reservations disagree with reserved holdings';
    END IF;

    IF EXISTS (
        SELECT 1 FROM trading_order_reservations r
        JOIN orders o ON o.order_id = r.order_id
        WHERE o.execution_status IN ('FILLED','CANCELLED','REJECTED')
          AND r.reserved_remaining <> 0
    ) THEN
        RAISE EXCEPTION 'AUDIT FAILED: terminal orders have nonzero reservations';
    END IF;

    -- A trade without two known distinct owners is not considered safe.
    IF EXISTS (
        SELECT 1 FROM trades t
        LEFT JOIN order_ownership b ON b.order_id = t.buy_order_id
        LEFT JOIN order_ownership s ON s.order_id = t.sell_order_id
        WHERE b.user_id IS NULL OR s.user_id IS NULL OR b.user_id = s.user_id
    ) THEN
        RAISE EXCEPTION 'AUDIT FAILED: trade with missing or identical counterparties';
    END IF;

    -- Direct inserts/corrupt reservations must also be flagged.
    IF EXISTS (
        SELECT 1 FROM trading_order_reservations r
        LEFT JOIN order_ownership owner ON owner.order_id = r.order_id
        LEFT JOIN exchange_commands c ON c.sequence_id = owner.submit_sequence_id
        WHERE owner.order_id IS NULL OR c.sequence_id IS NULL OR owner.user_id <> r.user_id
           OR c.command_type <> 'SUBMIT' OR c.symbol <> r.symbol
           OR c.side <> r.side OR c.price <> r.limit_price
    ) THEN
        RAISE EXCEPTION 'AUDIT FAILED: reservation and durable order ownership disagree';
    END IF;
END;
$audit$;

SELECT 'PASS: cash ledger, holding ledger, order reservations, trade ownership and terminal releases are consistent' AS audit;
COMMIT;
