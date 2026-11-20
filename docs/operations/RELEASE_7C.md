> **Archived milestone document:** This records the implementation process at that point in ExchangeX's development. Commands and limitations may be outdated; use the repository-root `README.md`, current `compose.yaml`, and `docs/releases/` for current behavior and release verification.

# ExchangeX — Sprint 7C release and disaster-recovery runbook

ExchangeX is an educational **simulated exchange**. It does not route orders to a market, custody funds, or satisfy financial-industry certification requirements. "Release-ready" means a validated portfolio deployment, not a real-money trading system.

## Scope delivered

- Online PostgreSQL 17 custom-format backup to a host-only directory (mode `0700`); each `.dump` and `.sha256` file is mode `0600`.
- SHA-256 verification, PostgreSQL archive TOC check and **restoration into a new disposable database only**. No `--clean` and no overwrite of the original database.
- Restore drill checks Flyway V1–V6, key schema tables, writer fence and existing financial invariants. The restored database is never pointed at by the running API.
- Read-only audit of legacy unowned active orders, unowned historic orders and stale/unowned commands; blocks release on unsafe live legacy state instead of guessing owners or editing ledger rows.
- Separate GitHub Actions release-readiness workflow with container smoke, duplicate-writer rejection, fencing-generation restart, SQL audits, online backup/restore and cleanup on ephemeral CI runners.
- Optional Caddy HTTPS/WSS edge sample with documented DNS, certificate, rate-limit and secrets limitations.

## First local execution (from the repository root)

Before any operation, check that your main branch includes Sprint 7B (Flyway `V6__exchange_writer_fence.sql`, `WriterLease.java`, matching engine security regression passing). Do not run this patch against a Sprint 6-only branch.

```bash
# Ensure .env exists and Docker is healthy.
docker compose up --build -d --wait --wait-timeout 240
mvn clean verify
python3 scripts/check-test-reports.py
bash scripts/smoke-compose.sh
bash scripts/audit-financial-invariants.sh

# Catalog historic issues without altering trading state.
bash scripts/audit-legacy-orders.sh

# Only on a disposable instance: restarts the API and checks writer fencing!
# ALLOW_DISRUPTIVE_FAILOVER_TEST=1 bash scripts/verify-writer-failover.sh

# Online backup and simulated disaster restore.
bash scripts/backup-postgres.sh
ls -lh backups/*.dump
bash scripts/verify-backup.sh backups/<YOUR_ARCHIVE_FILENAME>.dump
```

The backup command prints the exact archive and manifest locations. `verify-backup.sh` generates a random `exchangex_restore_drill_*` database, checks it, then drops *only* that generated database. A failed partial restore is left for manual inspection to avoid deleting unknown state.

**NEVER** use `docker compose down -v` on your existing development database unless you intentionally want to delete its volume. The CI workflow does so only on its disposable runner.

## Restoring for inspection

```bash
bash scripts/restore-postgres.sh backups/<YOUR_ARCHIVE_FILENAME>.dump exchangex_restore_review1234
# Inspect this isolated database using PostgreSQL tooling. Never point the live API at it.
```

The restore command refuses any database name not prefixed with `exchangex_restore_`, refuses an existing database, and requires a valid SHA-256 manifest by default (unless `ALLOW_UNVERIFIED_BACKUP=1` is explicitly set for an independently verified archive). Do not disable admission/fencing checks just to make a restoration appear successful.

## Scheduling backups and retention

Backups are automated by invoking the script from your host scheduler, after ensuring Docker Desktop or the PostgreSQL host is running. Example macOS/Linux crontab entry (replace the path with your actual checkout):

```cron
15 2 * * * /bin/bash /absolute/path/to/Exchangex/scripts/backup-postgres.sh >> /absolute/path/to/backup-operations.log 2>&1
```

Treat the `.dump` as **sensitive**: it contains password hashes and account data. Keep the local directory private; use encrypted, access-controlled offsite storage with tested retention. Never commit or attach a `.dump` or `.env`. If cron's `PATH` cannot find Docker Desktop's CLI, configure a valid absolute PATH in the scheduler. Backups in the same laptop/volume are not disaster recovery; off-host encrypted copies are necessary.

Do not implement unreviewed automatic deletion of backups. Establish RPO/RTO, schedule, retention and recovery credentials for your deployment and record regular restore-drill results. PostgreSQL `pg_dump` is logical backup, not point-in-time recovery (PITR); for PITR you need WAL archival and a full physical backup strategy.

## Legacy reconciliation / quarantine

The read-only audit reports the number of active unowned orders, historical unowned orders, stale pending commands and old unowned SUBMIT commands. It fails on active unowned orders or old unowned SUBMIT commands. For unresolved rows:

1. Stop new order admission and take a verified backup; preserve the original journal and logs.
2. Identify the source account from independently authenticated, immutable evidence. Never invent ownership or directly edit cash/holdings/reservations.
3. Reconstruct affected commands on an *isolated copy*. Compare journal/checkpoints/trade and ledger events, with a reviewed corrective migration only if justified.
4. Repeat all accounting and recovery tests before re-enabling admission. Historical unowned terminal records require a documented sign-off even if the active-order audit passes.

This patch deliberately **does not silently repair legacy balances**, because doing so without verified ownership could create fictitious assets and invalidate historic trades.

## HTTPS/WSS public demo (optional; not security certification)

1. Point a DNS A/AAAA record for a demo domain to the host. Allow inbound TCP 80/443 and UDP 443. Ensure no proxy already occupies those ports.
2. Keep `.env` untracked, generate high-entropy secrets and set `EXCHANGEX_DOMAIN=exchange.example.org` in `.env` (replace with your domain).
3. Start with `docker compose -f compose.yaml -f deploy/compose.tls.yaml up --build -d --wait`.
4. Verify `https://your-domain/`, HTTPS API, and `wss://` STOMP behavior. Caddy handles automatic TLS if DNS/ports permit. Its certificates persist in the `caddy_data` volume.
5. Verify client-IP controls at every proxy layer; a shared reverse-proxy IP can unintentionally put all external clients into one Nginx rate-limit bucket. Configure explicit **trusted-proxy** settings before exposing to untrusted networks. Never simply trust user-provided `X-Forwarded-For` headers.

The base Compose stack still loopback-publishes API, Grafana and Prometheus. Keep those ports firewall-restricted. For an internet-facing setup, use secret management outside plaintext Compose variables, rotate credentials, enforce outbound/ingress controls, avoid exposing actuator or PostgreSQL, conduct load testing and perform independent security assessment. Do not treat this sample as a finished production security posture.

## Sign-off checklist

- [ ] Source checkout is the fully merged Sprint 7B baseline, including Flyway V6 and bean-name correction.
- [ ] `mvn clean verify` reports zero failures, errors and skipped tests; `check-test-reports.py` passes.
- [ ] React build succeeds; Docker Compose smoke passes.
- [ ] Read-only financial and legacy audits pass on the intended database.
- [ ] Backups are nonempty, private and checksummed; isolated restore drill succeeds, with data invariants verified.
- [ ] Existing writer rejects a second concurrent API instance; cold restart with one instance recovers correctly.
- [ ] Public HTTPS/WSS demo is verified if enabled; secret storage, monitoring and backup cadence documented.
- [ ] GitHub Actions backend, frontend, Compose and release-readiness workflows are green on `main`.
- [ ] User login, two distinct funded traders, order lifecycle and WebSocket updates are manually verified after deployment.

If any box remains unchecked, do not describe the deployment as fully validated. Tests are evidence of specific behaviors, not a proof against every security or financial correctness defect.
