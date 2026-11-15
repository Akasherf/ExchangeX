-- ExchangeX: Durable execution schema additions
-- Version 2

-- 1. Command checkpoint: tracks last fully-committed sequence per symbol
CREATE TABLE command_checkpoint (
    symbol VARCHAR(10) PRIMARY KEY REFERENCES instruments(symbol),
    last_completed_sequence_id BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Initialize checkpoint for AAPL
INSERT INTO command_checkpoint (symbol, last_completed_sequence_id)
VALUES ('AAPL', 0)
ON CONFLICT (symbol) DO NOTHING;

-- 2. Add processing status to exchange_commands
ALTER TABLE exchange_commands ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'));
ALTER TABLE exchange_commands ADD COLUMN completed_at TIMESTAMPTZ;

-- Unique constraint on client order_id for SUBMIT commands (idempotency)
CREATE UNIQUE INDEX idx_commands_client_order_id
    ON exchange_commands(order_id)
    WHERE command_type = 'SUBMIT';

-- 3. Enhance orders table for execution tracking
ALTER TABLE orders ADD COLUMN client_order_id BIGINT NOT NULL UNIQUE;
ALTER TABLE orders ADD COLUMN exchange_sequence_id BIGINT NOT NULL REFERENCES exchange_commands(sequence_id);
ALTER TABLE orders ADD COLUMN execution_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (execution_status IN ('PENDING', 'PARTIAL', 'FILLED', 'CANCELLED', 'REJECTED'));

-- Backfill existing orders (if any) - client_order_id = order_id, sequence_id = 0 (pre-durable)
-- Note: V1 orders table may be empty in fresh deployments
UPDATE orders SET
    client_order_id = order_id,
    exchange_sequence_id = 0,
    execution_status = CASE
        WHEN status = 'OPEN' THEN 'PENDING'
        WHEN status = 'PARTIALLY_FILLED' THEN 'PARTIAL'
        WHEN status = 'FILLED' THEN 'FILLED'
        WHEN status = 'CANCELLED' THEN 'CANCELLED'
    END
WHERE client_order_id IS NULL;

-- Make client_order_id NOT NULL after backfill (already declared NOT NULL above, but this ensures constraint)
-- The ADD COLUMN with NOT NULL requires a default or the table to be empty.
-- Since we backfill above, this is safe if table was empty or we provide default.

-- 4. Enhance trades table with command sequence references for replay verification
ALTER TABLE trades ADD COLUMN buy_sequence_id BIGINT NOT NULL REFERENCES exchange_commands(sequence_id);
ALTER TABLE trades ADD COLUMN sell_sequence_id BIGINT NOT NULL REFERENCES exchange_commands(sequence_id);

-- Backfill trades (sequence_id = 0 for pre-durable trades)
UPDATE trades SET
    buy_sequence_id = 0,
    sell_sequence_id = 0
WHERE buy_sequence_id IS NULL;

-- 5. Indexes for replay and lookup performance
CREATE INDEX idx_orders_client_order_id ON orders(client_order_id);
CREATE INDEX idx_orders_exchange_sequence_id ON orders(exchange_sequence_id);
CREATE INDEX idx_trades_buy_sequence_id ON trades(buy_sequence_id);
CREATE INDEX idx_trades_sell_sequence_id ON trades(sell_sequence_id);
CREATE INDEX idx_commands_status_symbol ON exchange_commands(status, symbol, sequence_id);