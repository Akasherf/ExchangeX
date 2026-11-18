-- Sprint 7B: a monotonically increasing generation fences previous process owners.
-- One row protects the whole ExchangeX process, not one row per instrument.
CREATE TABLE exchange_writer_fence (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    generation BIGINT NOT NULL CHECK (generation >= 0),
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO exchange_writer_fence(id, generation) VALUES (1, 0);
