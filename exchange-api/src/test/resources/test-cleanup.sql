-- Test cleanup script - runs before each test method
DELETE FROM trades;
DELETE FROM orders;
DELETE FROM exchange_commands;
DELETE FROM command_checkpoint;
INSERT INTO command_checkpoint (symbol, last_completed_sequence_id) VALUES ('AAPL', 0);