-- Test cleanup script - runs before each test method
DELETE FROM trades;
DELETE FROM orders;
DELETE FROM exchange_commands;
-- Use UPSERT to reset checkpoint (INSERT fails if row exists due to PK)
INSERT INTO command_checkpoint (symbol, last_completed_sequence_id) VALUES ('AAPL', 0)
ON CONFLICT (symbol) DO UPDATE SET last_completed_sequence_id = 0, updated_at = NOW();