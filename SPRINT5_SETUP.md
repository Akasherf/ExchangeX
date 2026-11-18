# ExchangeX Sprint 5 — WebSocket + React Trading Terminal

## Contents

- `exchange-api/src/main/java/com/exchangex/api/stream`: STOMP endpoint, CONNECT JWT verification, destination authorization, durable-state-based market snapshots, best-effort publisher, private order updates and authenticated order-history REST endpoint.
- `exchange-web`: React/Vite user interface.
- No matching-engine, coordinator settlement, ledger, migration, or checkpoint algorithm changes.

## Prerequisites

- **Committed Sprint 4** on `main` or `feature/sprint4-integrated`. Review your working tree before applying.
- Java 21, Maven, PostgreSQL on localhost:5433, Node 22.12+ and npm.
- A production-like JWT secret: `export EXCHANGEX_JWT_SECRET_BASE64="$(openssl rand -base64 32)"` (generate once per environment and preserve securely; changing the key invalidates existing tokens).
- The existing `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` variables.
- A dedicated PostgreSQL test database `exchangex_test` and `EXCHANGEX_TEST_DB_URL`, `EXCHANGEX_TEST_DB_USERNAME`, `EXCHANGEX_TEST_DB_PASSWORD` environment variables for the integration suite.

## Apply the patch

```sh
cd ~/Downloads/Exchangex
git status --short
git switch -c feature/sprint5-realtime-dashboard
git apply --check ~/Downloads/ExchangeX-Sprint5-Realtime-Dashboard.patch
git apply ~/Downloads/ExchangeX-Sprint5-Realtime-Dashboard.patch
git diff --check
```

If the branch exists, switch to it only after verifying the working tree. Do not reapply an already-applied patch. Avoid `git add .` because local private notes should remain untracked.

## Backend verification

```sh
mvn -pl exchange-api -am -Dtest=StreamInboundAuthTest,StreamDestinationPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean verify
```

**Acceptance gate:** Zero failures, zero errors, zero unexpected skips. Full PostgreSQL regression suite may take several minutes.

## Run locally

Terminal A, project root:

```sh
mvn -pl exchange-api -am spring-boot:run
```

If Maven reports that it cannot resolve a reactor dependency, compile the project first with `mvn -DskipTests package`, then use `java -jar exchange-api/target/exchange-api-0.1.0-SNAPSHOT.jar`.

Terminal B:

```sh
cd ~/Downloads/Exchangex/exchange-web
npm install
npm run build
npm run dev
```

Open `http://127.0.0.1:5173` (Vite proxies `/api` and `/ws` to port 8085). The backend must have applied Flyway V1–V5 and `exchangex.stream.enabled=true`, `exchangex.security.enabled=true`. Never commit JWT or DB secrets.

## Contract

| Channel | Authentication | Payload |
| --- | --- | --- |
| GET `/api/v1/market/instruments/AAPL/snapshot` | Public | Symbol, checkpoint sequence, best bid/ask, recent executions |
| WS `/ws`, STOMP subscribe `/topic/market/AAPL` | Anonymous allowed | Same market snapshot, emitted when checkpoint advances |
| STOMP subscribe `/user/queue/orders` | JWT in STOMP CONNECT header | Own order status events, best-effort only |
| GET `/api/v1/account/orders` | JWT | Own last 100 persisted orders |
| POST `/api/v1/instruments/{symbol}/orders` | JWT | Existing durable order admission |
| DELETE `/api/v1/instruments/{symbol}/orders/{id}` | JWT | Existing cancellation admission |

Only `AAPL`, `MSFT`, and `GOOGL` are seeded in V1 and V5. The market snapshot is best-effort across independent queries; it is not an atomic matching-engine state snapshot. The broker is in-process. A reconnection is followed by REST resynchronization, and the frontend periodically refreshes every 15 seconds.

## Important restrictions

- No public funds/deposit/grant endpoint. New users start with zero cash and holdings. Use only the existing controlled testing/fixture procedures when demonstrating funded trading on a **disposable database**.
- WebSocket handshake can be anonymous for public market data, but private destinations require a validated active-account JWT in the STOMP CONNECT `Authorization` header. Tokens are not placed in URLs.
- Browser app stores token in `sessionStorage`, not persistent `localStorage`. Treat XSS, TLS, secure token lifecycle and proper deployment configuration as remaining hardening work.
- A private message is a hint, not durable acknowledgement. Refresh `/account/orders` and `/account` for authoritative state after missed events or reconnections.
- Polling private orders is capped at 5,000 newest persisted orders. A production system should use transactional outbox + an external message broker, idempotent event versions, rate limits and backpressure.
- For tests using the `postgres-integration` profile, streaming is disabled to avoid background reads during destructive integration tests.
- It is a simulator, not an investment or brokerage platform.

## Manual smoke test

1. Start API. Check `curl http://127.0.0.1:8085/api/v1/instruments` and `curl http://127.0.0.1:8085/api/v1/market/instruments/AAPL/snapshot`.
2. Start UI. Confirm AAPL, MSFT and GOOGL tabs show market-data states without logging in.
3. Register an account, log in, check that zero available funds/positions display accurately.
4. Confirm an unfunded BUY is rejected, not silently filled.
5. Using **existing controlled test seeding procedures on a disposable database**, fund two simulation accounts, place crossing BUY/SELL orders, and confirm the trade tape and private order statuses update.
6. Disconnect the API/WS connection, reconnect, and verify the UI refreshes from REST instead of pretending missed events arrived.
7. Try direct STOMP subscription to `/user/queue/orders` without JWT and client SEND to `/topic/market/AAPL`; both must be rejected.
