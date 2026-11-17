-- Sprint 4.1: durable identity and ownership. Existing legacy orders stay unclaimed.
CREATE TABLE exchange_users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT email_normalized CHECK (email = LOWER(TRIM(email)))
);

CREATE TABLE order_ownership (
    order_id BIGINT PRIMARY KEY,
    submit_sequence_id BIGINT NOT NULL UNIQUE
        REFERENCES exchange_commands(sequence_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES exchange_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_order_ownership_user ON order_ownership(user_id, order_id);
