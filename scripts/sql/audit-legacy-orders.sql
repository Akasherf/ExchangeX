-- Snapshot-only legacy-order audit. Never rewrites financial data.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'UNOWNED_ACTIVE_ORDER' AS finding, COUNT(*) AS count
FROM orders o LEFT JOIN order_ownership own ON own.order_id=o.order_id
WHERE own.order_id IS NULL AND o.status IN ('OPEN','PARTIALLY_FILLED');

SELECT 'UNOWNED_HISTORICAL_ORDER' AS finding, COUNT(*) AS count
FROM orders o LEFT JOIN order_ownership own ON own.order_id=o.order_id
WHERE own.order_id IS NULL AND o.status IN ('FILLED','CANCELLED');

SELECT 'UNOWNED_OLD_SUBMIT' AS finding, COUNT(*) AS count
FROM exchange_commands c LEFT JOIN order_ownership own ON own.submit_sequence_id=c.sequence_id
WHERE c.command_type='SUBMIT' AND own.order_id IS NULL
  AND c.created_at < now()-interval '5 minutes';

SELECT 'STALE_PENDING_COMMAND' AS finding, COUNT(*) AS count
FROM exchange_commands
WHERE status='PENDING' AND created_at < now()-interval '5 minutes';

DO $audit$
BEGIN
  IF EXISTS (
    SELECT 1 FROM orders o LEFT JOIN order_ownership own ON own.order_id=o.order_id
    WHERE own.order_id IS NULL AND o.status IN ('OPEN','PARTIALLY_FILLED')
  ) THEN
    RAISE EXCEPTION 'BLOCKED: unowned active legacy orders. Freeze new trading, take a backup, investigate; do not assign owners automatically.';
  END IF;
  IF EXISTS (
    SELECT 1 FROM exchange_commands c LEFT JOIN order_ownership own ON own.submit_sequence_id=c.sequence_id
    WHERE c.command_type='SUBMIT' AND own.order_id IS NULL
      AND c.created_at < now()-interval '5 minutes'
  ) THEN
    RAISE EXCEPTION 'BLOCKED: unowned historic SUBMIT commands; investigate before enabling enforced trading.';
  END IF;
END;
$audit$;
SELECT 'PASS: no active unowned legacy orders or old unowned SUBMIT commands' AS status;
COMMIT;
