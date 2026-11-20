> **Archived milestone document:** This records the implementation process at that point in ExchangeX's development. Commands and limitations may be outdated; use the repository-root `README.md`, current `compose.yaml`, and `docs/releases/` for current behavior and release verification.

# ExchangeX Sprint 7B — Security, traffic control, and single-writer fencing

This increment protects the existing durable trading implementation. It does not replace matching, idempotency, settlement, or database recovery code.

## What is implemented

1. **Exclusive process writer:** `WriterLease` borrows one dedicated PostgreSQL connection and holds a session advisory lock for the entire coordinator lifetime. A second API process using the same database fails startup rather than joining another matching writer.
2. **Transaction fencing:** Flyway V6 introduces `exchange_writer_fence`. Every journal admission and execution commit locks its fencing-generation row (`SELECT ... FOR UPDATE`) inside its transaction before modifying command, trade, order, settlement, or checkpoint state. Successful takeover increments the generation *before* recovery. An old disconnected writer is rejected if it attempts another transaction after takeover.
3. **Health:** a `writerLease` health contributor goes DOWN if the dedicated lock connection cannot be validated. The normal Spring Boot health endpoint includes it.
4. **Login and signup limits:** local bounded memory limits apply on direct API requests (`/login`: 60/min per peer; `/register`: 20/min). The public Nginx entry point also enforces client-IP limits (`/login`: 5/min with burst 10; `/register`: 2/min with burst 5), responding HTTP 429. These are complementary; do not rely on a local-only per-instance limiter when deploying more than one edge replica.
5. **WebSocket protection:** Nginx limits WebSocket handshake rate and concurrent connections per client IP. STOMP interceptor enforces at most 200 connected sessions per process, 8 subscriptions/session, 120 inbound frames/minute/session, maximum 8192-byte STOMP payload, 4096-character authorization token, and short destination/identifier limits. Outbound markets remain public and private queues remain JWT-authenticated. Client SEND remains blocked.
6. **Regression coverage:** tests cover session/subscription/frame limits, local auth limits, and an exclusive-writer/reacquisition PostgreSQL integration case.

## Operational prerequisites

- Java 21, Maven 3.9+, PostgreSQL 17, Docker Compose.
- **V6 migration required before starting the new API.** Do not run this API against a database that disallows migrations.
- No manual writes to `exchange_writer_fence`. Do not bypass fencing using configuration. `exchangex.writer.guard.enabled=false` is forbidden except under the `test` and `postgres-integration` profiles used by isolated integration tests.
- The writer lease consumes one JDBC connection from the Hikari pool. Maintain reasonable pool sizing.
- Only one ExchangeX API instance may process a given database. Do **not** horizontally scale the API service in Compose/Kubernetes. Read-only replicas require a separately designed topology.
- Set the Nginx client-IP correctly through a **trusted** reverse proxy if putting a load balancer in front of Nginx. Don't trust arbitrary `X-Forwarded-For` headers. The backend intentionally uses the actual socket address, never an untrusted forwarded header.
- The Nginx Docker frontend is exposed on `127.0.0.1:8088` during development. API port `127.0.0.1:8085` is also loopback-bound. A public deployment requires TLS/WSS, firewall rules, external secret management, and careful management endpoint isolation.

## Validation before merge

```bash
# Use the isolated PostgreSQL integration-test DB variables used in previous sprints.
mvn clean verify
python3 scripts/check-test-reports.py

docker compose up --build -d --wait --wait-timeout 240
bash scripts/smoke-compose.sh
bash scripts/audit-financial-invariants.sh
```

**Manual single-writer test:** with the normal ExchangeX Compose API running and healthy, attempt to start a *second* API using the same database and matching secrets. Its startup must fail with `Another ExchangeX writer already owns this PostgreSQL database`. Do not run multiple matching workers as a resilience test; rejected startup is the expected outcome. Then stop the first API and verify a newly started API can acquire the lease, recover its checkpoint, and resume correctly. For a safe manual test, use a disposable copy of the database if you want to simulate connection drops.

**Manual traffic checks:** use a test user/isolated environment. Repeated requests via the published Nginx endpoint should receive HTTP 429 and `Retry-After` from the API's in-process limiter where applicable; Nginx's edge limiter also returns 429. Do not intentionally flood production endpoints or shared CI runners.

**WebSocket checks:** connect anonymously to a public symbol; verify private subscriptions still require a valid JWT; simulate more than 8 subscriptions and confirm the server rejects excess requests; validate reconnection after a normal disconnect. Confirm that the dashboard still works on `http://127.0.0.1:8088` with normal activity.

**Data integrity:** execute a funded transaction between *different* users, confirm self-trade is still rejected, execute `scripts/audit-financial-invariants.sh`, and verify changing checkpoints and Grafana metrics. Existing rollback and crash recovery tests must pass with zero skips.

## Remaining limitations

- This is **single-active-writer fencing**, not distributed active/active matching or automatic leader election. A new writer is admitted only when PostgreSQL releases the previous advisory lock.
- Process-local rate-limit and STOMP session counters reset on restart. Client-IP throttling at Nginx is also per Nginx process, not a cluster-wide distributed quota. Public distributed deployments require a shared limiting store, WAF and limits on upstream connections.
- Keep the frontend Nginx as the public entry point. Don't expose the management API externally.
- Load tests, disaster-recovery backups, production TLS/secret rotation, and legacy-order reconciliation remain release-readiness work; passing Sprint 7B alone does not certify a real-money exchange.
