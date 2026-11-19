-- Read-only smoke test of an isolated, restored ExchangeX database.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
DO $verify$
DECLARE
  required_table text;
  migration_count integer;
BEGIN
  FOREACH required_table IN ARRAY ARRAY[
    'instruments','exchange_commands','command_checkpoint','orders','trades',
    'exchange_users','order_ownership','trading_accounts','account_holdings',
    'cash_ledger_events','holding_ledger_events','trading_order_reservations',
    'exchange_writer_fence','flyway_schema_history'
  ] LOOP
    IF to_regclass('public.' || required_table) IS NULL THEN
      RAISE EXCEPTION 'RESTORE FAILED: missing table %', required_table;
    END IF;
  END LOOP;

  SELECT COUNT(*) INTO migration_count FROM flyway_schema_history
  WHERE success AND (CASE WHEN version ~ '^[0-9]+$' THEN version::integer END) BETWEEN 1 AND 6;
  IF migration_count < 6 THEN
    RAISE EXCEPTION 'RESTORE FAILED: expected successful Flyway migrations V1 through V6, found %', migration_count;
  END IF;

  IF (SELECT COUNT(*) FROM exchange_writer_fence WHERE id=1) <> 1 THEN
    RAISE EXCEPTION 'RESTORE FAILED: invalid writer fence cardinality';
  END IF;
  IF EXISTS(SELECT 1 FROM exchange_writer_fence WHERE generation < 0) THEN
    RAISE EXCEPTION 'RESTORE FAILED: negative writer generation';
  END IF;
END;
$verify$;
SELECT 'PASS: restored ExchangeX V1-V6 schema and writer fence' AS status;
COMMIT;
