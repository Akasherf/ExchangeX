-- Sprint 4: funded order reservations and additional instruments.
-- Financial settlement is enabled by the application feature gate only after
-- operators have resolved legacy unowned live orders/pending SUBMIT commands.
INSERT INTO instruments(symbol, active) VALUES ('MSFT', TRUE), ('GOOGL', TRUE)
ON CONFLICT (symbol) DO NOTHING;
INSERT INTO command_checkpoint(symbol, last_completed_sequence_id)
SELECT symbol, 0 FROM instruments
ON CONFLICT (symbol) DO NOTHING;

CREATE TABLE trading_order_reservations (
    order_id BIGINT PRIMARY KEY REFERENCES order_ownership(order_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES trading_accounts(user_id),
    symbol VARCHAR(10) NOT NULL REFERENCES instruments(symbol),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    limit_price BIGINT NOT NULL CHECK (limit_price > 0),
    reserved_remaining BIGINT NOT NULL CHECK (reserved_remaining >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_reservations_user_symbol ON trading_order_reservations(user_id, symbol);

ALTER TABLE cash_ledger_events DROP CONSTRAINT cash_ledger_events_event_type_check;
ALTER TABLE cash_ledger_events ADD CONSTRAINT cash_ledger_events_event_type_check
    CHECK (event_type IN ('SIM_CREDIT','RESERVE','RELEASE','SETTLEMENT','TRADE_CREDIT'));
ALTER TABLE holding_ledger_events DROP CONSTRAINT holding_ledger_events_event_type_check;
ALTER TABLE holding_ledger_events ADD CONSTRAINT holding_ledger_events_event_type_check
    CHECK (event_type IN ('SIM_GRANT','RESERVE','RELEASE','SETTLEMENT','TRADE_CREDIT'));
