# ExchangeX Sprint 6 — Docker, CI and Observability

This is a **local, single-node, simulation-only** deployment. It is **not a production exchange**. All endpoints are bound to localhost; TLS, image-signing, distributed writers and production secret-management remain Sprint 7 concerns.

## 0. Requirements

- Docker Desktop with Docker Compose v2 and enough available memory for Maven image compilation, PostgreSQL, Spring Boot, Prometheus and Grafana (suggest >= 6 GiB for Docker Desktop).
- A committed `exchange-web/package-lock.json` (run `cd exchange-web && npm install --package-lock-only` once if missing, then commit it; Docker uses `npm ci`).
- A clean Sprint 5 branch with completed baseline end-to-end tests. No manually seeded production or imported historic DB volume.
- Ports **8088**, **8085**, **9091**, **3000** available on `127.0.0.1`.

## 1. Create a feature branch, apply the patch

```bash
cd ~/Downloads/Exchangex
git status --short
git switch -c feature/sprint6-devops-monitoring
git apply --check ~/Downloads/ExchangeX-Sprint6-DevOps-Monitoring.patch
git apply ~/Downloads/ExchangeX-Sprint6-DevOps-Monitoring.patch
git diff --check
```

If your patch is already applied, do not apply it again. If your tree diverged, review conflicts instead of using `--3way` blindly.

## 2. Generate private local secrets

```bash
bash scripts/init-local-env.sh
chmod 600 .env
```

The generated `.env` holds PostgreSQL credentials, Grafana admin password and a long-lived local JWT secret. **Never commit `.env`, paste its values into chat, or rotate JWT secret without intending to invalidate tokens.** Compose volumes persist data and are deliberately not deleted by regular `down`.

## 3. Build and start

```bash
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
docker compose ps
bash scripts/smoke-compose.sh
```

The first build downloads Maven/npm/image dependencies and is slower than subsequent starts. Services are deliberately isolated across `data`, `frontend` and `monitoring` networks. The API is single-replica to honor coordinator assumptions; **never scale the `api` service horizontally**.

| Component | Local URL | Notes |
| --- | --- | --- |
| React terminal | http://127.0.0.1:8088 | Nginx proxies only `/api/` and `/ws` |
| Backend | http://127.0.0.1:8085/api/v1/instruments | localhost only; no public hosting |
| Prometheus | http://127.0.0.1:9091 | local console (internal target `api:9090`) |
| Grafana | http://127.0.0.1:3000 | username `admin`, password from `.env` |

Prometheus scrape path: `http://api:9090/actuator/prometheus`. Management port `9090` is **not published**, and Nginx does not proxy it. `/actuator/prometheus` is permit-all on the *internal* management listener. Do not expose API/management ports, compose networks, or Prometheus/Grafana consoles to the Internet as-is.

## 4. Verify Prometheus and dashboard

- Prometheus > Status > Targets: `exchangex-api` **UP**.
- Grafana > Dashboards > ExchangeX > `ExchangeX · Trading and Runtime`.
- Verify `exchangex_instrument_checkpoint_sequence{symbol="AAPL"}` (also `MSFT`, `GOOGL`) and `exchangex_instrument_trades_committed`.
- All three symbols should have a zero baseline on a clean database. Create valid funded test orders only on disposable development data and observe checkpoint/trades counters update.
- Check the JVM/HTTP graphs. Empty HTTP graphs may remain until requests are made.
- Prometheus ships with two **local recording alert rules** (unavailable scrape and high 5xx rate). **There is no Alertmanager notification delivery configured**; Sprint 7 can add routing.
- No production latency/throughput benchmark is claimed here.

## 5. GitHub Actions

`ci.yml` checks Java 21 + a real PostgreSQL 17 service, rejects skipped Surefire tests, builds the Vite frontend with a committed npm lockfile, then builds/starts Compose and smoke-checks HTTP, metrics, Prometheus and Grafana. On a new branch, open a pull request and check all three jobs.

The current baseline is >= 92 Surefire cases with **zero skipped**, and this sprint adds a focused metrics test. A skipped PostgreSQL test due to missing DB env is a CI failure, not success.

## 6. Verify the core trading workflow (manual)

With Compose running, use two distinct accounts in separate browser sessions and provision simulation balances using your controlled development-only fixture. Submit crossing BUY/SELL limit orders, verify the committed trade and account ledger, confirm browser STOMP frames, restart the API and verify REST resynchronization. The new infrastructure does not bypass the funded-order migration safeguard and does not add an open money-creation endpoint.

## 7. Troubleshooting and safe cleanup

```bash
docker compose logs --tail=150 api
docker compose logs --tail=150 web
docker compose logs --tail=150 prometheus
docker compose logs --tail=150 grafana
docker compose restart api
docker compose down
```

`docker compose down` **preserves** PostgreSQL, Prometheus and Grafana named volumes. Avoid `down -v` on any environment with data you want to keep. New user accounts start with **zero cash/holdings**. Do not mount the old local `exchangex` DB (contains legacy unreserved orders); Compose creates a fresh dedicated volume.

## 8. Security and engineering boundaries

- `.env` is only a developer convenience; for deployments, use a secret manager and external databases.
- Authentication remains JWT + BCrypt; public market data is not an entitlement to private order streams.
- New instrument metrics read committed PostgreSQL data, sample at 15-second intervals, emit only AAPL/MSFT/GOOGL tag values, and cannot alter trade settlement.
- Metrics and dashboard deployment are local only, not verified distributed/HA; in-process STOMP messages remain best-effort. An external broker/outbox and TLS/WSS are future work.
- Existing Sprint 3 metrics may remain AAPL-centric; the new gauges explicitly add per-symbol coverage for checkpoint, committed trades and open resting orders. Do not claim exhaustive per-symbol latency instrumentation.
