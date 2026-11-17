-- Sprint 4.2A: integer-denominated simulation balances, holdings and event journals.
-- Cash units are abstract exchange units; price tick conversion/settlement comes in 4.2B.
-- These balances are NOT yet enforced by the order coordinator.
CREATE TABLE trading_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES exchange_users(id),
    available_cash BIGINT NOT NULL DEFAULT 0 CHECK (available_cash >= 0),
    reserved_cash BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cash >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE account_holdings (
    user_id BIGINT NOT NULL REFERENCES trading_accounts(user_id),
    symbol VARCHAR(10) NOT NULL REFERENCES instruments(symbol),
    available_quantity BIGINT NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity BIGINT NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, symbol)
);

-- Unique event keys make retries idempotent. Events are append-only by application policy.
CREATE TABLE cash_ledger_events (
    user_id BIGINT NOT NULL REFERENCES trading_accounts(user_id),
    event_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN ('SIM_CREDIT', 'RESERVE', 'RELEASE')),
    available_delta BIGINT NOT NULL,
    reserved_delta BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, event_key),
    CONSTRAINT cash_event_nonzero CHECK (available_delta <> 0 OR reserved_delta <> 0)
);

CREATE TABLE holding_ledger_events (
    user_id BIGINT NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN ('SIM_GRANT', 'RESERVE', 'RELEASE')),
    available_delta BIGINT NOT NULL,
    reserved_delta BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, symbol, event_key),
    FOREIGN KEY (user_id, symbol) REFERENCES account_holdings(user_id, symbol),
    CONSTRAINT holding_event_nonzero CHECK (available_delta <> 0 OR reserved_delta <> 0)
);

-- Migration is safe for users who registered before V4.
INSERT INTO trading_accounts(user_id)
SELECT id FROM exchange_users
ON CONFLICT (user_id) DO NOTHING;
