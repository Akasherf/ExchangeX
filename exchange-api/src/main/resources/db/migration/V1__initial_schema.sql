
-- ExchangeX: Initial database schema
-- Version 1

CREATE TABLE instruments (
    symbol VARCHAR(10) PRIMARY KEY,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE exchange_commands (
    sequence_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    command_type VARCHAR(10) NOT NULL
        CHECK (command_type IN ('SUBMIT', 'CANCEL')),
    order_id BIGINT NOT NULL,
    symbol VARCHAR(10) NOT NULL
        REFERENCES instruments(symbol),
    side VARCHAR(4)
        CHECK (side IN ('BUY', 'SELL')),
    price BIGINT,
    quantity BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT valid_command_payload CHECK (
        (command_type = 'SUBMIT'
            AND side IS NOT NULL
            AND price > 0
            AND quantity > 0)
        OR
        (command_type = 'CANCEL'
            AND side IS NULL
            AND price IS NULL
            AND quantity IS NULL)
    )
);

CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL
        REFERENCES instruments(symbol),
    side VARCHAR(4) NOT NULL
        CHECK (side IN ('BUY', 'SELL')),
    limit_price BIGINT NOT NULL CHECK (limit_price > 0),
    original_quantity BIGINT NOT NULL
        CHECK (original_quantity > 0),
    remaining_quantity BIGINT NOT NULL
        CHECK (remaining_quantity >= 0),
    status VARCHAR(20) NOT NULL
        CHECK (status IN (
            'OPEN', 'PARTIALLY_FILLED',
            'FILLED', 'CANCELLED'
        )),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT valid_remaining_quantity CHECK (
        remaining_quantity <= original_quantity
    )
);

CREATE TABLE trades (
    trade_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    buy_order_id BIGINT NOT NULL REFERENCES orders(order_id),
    sell_order_id BIGINT NOT NULL REFERENCES orders(order_id),
    execution_price BIGINT NOT NULL CHECK (execution_price > 0),
    execution_quantity BIGINT NOT NULL
        CHECK (execution_quantity > 0),
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT distinct_trade_orders CHECK (
        buy_order_id <> sell_order_id
    )
);

CREATE INDEX idx_orders_symbol_status
    ON orders(symbol, status);

CREATE INDEX idx_commands_symbol_sequence
    ON exchange_commands(symbol, sequence_id);

INSERT INTO instruments(symbol, active)
VALUES ('AAPL', TRUE);
