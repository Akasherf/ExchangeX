# ExchangeX — Sprint 7A: Self-trade prevention and ledger audit

## Why this change is needed

Previously, an account could submit opposite-side orders that crossed itself. The matching engine was unaware of order ownership and might produce a fill with identical buyer/seller. This sprint adds a **funded-trading admission-time, fail-closed check** without modifying the matching engine, event journal format, transaction boundaries, or recovery engine.

This is a **hardening increment**, not a declaration that the exchange is production-ready.

## Enforcement contract

1. A new funded SUBMIT obtains the existing PostgreSQL advisory transaction lock for its instrument.
2. Within the **same admission transaction**, it searches the requesting account's opposite-side submitted orders.
3. A submitted order counts as active if (a) it has a committed order projection with remaining quantity > 0 and `execution_status` `PENDING`/`PARTIAL`, **or** (b) its SUBMIT command is still `PENDING` and it has no projection yet.
4. Crossing limits are determined using integer prices: an incoming BUY crosses a same-owner SELL if SELL limit <= BUY limit; an incoming SELL crosses a same-owner BUY if BUY limit >= SELL limit.
5. If crossing: rollback the entire new admission (journal, ownership and any reservation). The REST API responds `409` with `SELF_TRADE_PREVENTED`. Retry with the **same original order ID and payload** still preserves its original idempotent response; existing orders are not retroactively rejected.
6. A matched trade also checks buyer != seller before any transfer as a **defensive backstop**. If this backstop ever fires, the coordinator fails closed and requires operator investigation before replay; it is not an automatic cancel or repair process.

The self-cross guard is unconditional whenever funded trading is enforced; there is no configuration flag to disable it. The admission lock protects the check against simultaneous arrivals **in a single shared PostgreSQL cluster**; it does not provide leader election or fencing for multiple concurrent matching-engine processes.

**Limitations:** There is no configurable cancel-maker/cancel-taker STP policy, no grouping of beneficially owned accounts, and no admin whitelist. Existing unowned/legacy orders require an audited migration. The public HTTP API remains local-only; this patch does not add authentication rate limiting, TLS, WSS, or distributed fencing.

## Regression tests

Run the tests in your *disposable* `exchangex_test` database only:

```bash
cd ~/Downloads/Exchangex
export EXCHANGEX_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5433/exchangex_test'
export EXCHANGEX_TEST_DB_USERNAME='exchangex'
read -rs 'EXCHANGEX_TEST_DB_PASSWORD?Test database password: '; echo
export EXCHANGEX_TEST_DB_PASSWORD
mvn -pl exchange-api -am -Dtest=FundedTradingIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean verify
```

The `FundedTradingIntegrationTest` now includes checks for existing and pending orders, permitted noncrossing orders, successful cross-account execution, no failed-admission ledger mutations, and cancellation releases. They supplement existing tests for funded settlement, ownership and recovery.

## Read-only financial integrity audit

Run **after Compose starts**, and again after simulated trades and cancellation:

```bash
bash scripts/audit-financial-invariants.sh
```

The audit runs in a single `REPEATABLE READ READ ONLY` PostgreSQL transaction and returns a nonzero exit status on a detected violation. It compares account cash/holdings to ledger event deltas, live reservation aggregates to reserved balances, completed/cancelled orders to zero remaining reservation, trade counterparties to distinct owners, and reservation owners to recorded submitted commands.

The audit is safe to run against the Compose database and **does not modify trading state**. It is *not* a substitute for financial reconciliation by external systems, audit logging, point-in-time recovery, or production database backups.

## Remaining Sprint 7 hardening work

- Authentication rate limits and account lockout policy (with coordinated backing store rather than per-process assumptions).
- WebSocket abuse controls, message-size limits and backpressure on outbound subscriptions.
- Explicit single-active-writer fencing or distributed leader election before horizontal scaling.
- Audited remediation for legacy/unreserved orders (do not delete old order history).
- Backup/restore drills, out-of-space fault injection, PostgreSQL transaction outage drills, and capacity testing.
- HTTPS/WSS termination, secrets lifecycle, deployment controls, and a public demo strategy that does not expose privileged simulation grants.
- Confirm full GitHub Actions CI is green; do not claim readiness from local unit tests alone.

For portfolio claims, describe this sprint as **self-trade prevention, defensive settlement validation, and read-only account reconciliation** rather than "production-ready exchange."
