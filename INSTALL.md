# Install and run ExchangeX

This guide applies to the current Java 21 / Spring Boot / React / PostgreSQL / Docker Compose exchange simulator. Historical sprint patch instructions are preserved under [docs/development](docs/development/) and must not be applied to this checkout.

## Prerequisites

- Docker Engine or Docker Desktop with Compose v2
- Git, Bash, OpenSSL and `curl`
- Sufficient resources for PostgreSQL, API, frontend, Prometheus and Grafana (6 GiB or more available to Docker is a useful starting point)

## Start the local development/demo stack

```bash
git clone https://github.com/Akasherf/ExchangeX.git
cd ExchangeX
bash scripts/init-local-env.sh
docker compose up --build -d --wait --wait-timeout 240
bash scripts/smoke-compose.sh
```

`scripts/init-local-env.sh` creates a private, randomly generated `.env` on first execution and **leaves any existing `.env` untouched**. Save credentials securely. Never commit `.env`.

| Service | Local address |
| --- | --- |
| Trading dashboard | http://127.0.0.1:8088 |
| REST API | http://127.0.0.1:8085 |
| Prometheus | http://127.0.0.1:9091 |
| Grafana | http://127.0.0.1:3000 |

PostgreSQL is internal to the Compose network; the management port `9090` is not published to the host. The Compose deployment binds published services to loopback. Do not expose these interfaces to untrusted networks as-is.

## Validate the running application

```bash
docker compose ps
bash scripts/smoke-compose.sh
bash scripts/audit-financial-invariants.sh
curl --fail http://127.0.0.1:8085/api/v1/instruments
```

The smoke test checks container availability, UI, proxied API, internal matching-coordinator readiness, metrics, Prometheus and Grafana. The financial audit is read-only. Neither proves a complete live order lifecycle under a simulated outage.

New accounts start with **zero simulated cash and positions**. The application deliberately has no unauthenticated funding endpoint. To exercise funded matching, use controlled disposable test fixtures and follow the security/recovery test procedures; do not insert arbitrary accounting records into existing databases.

## Build and test source independently

```bash
mvn -B -ntp clean verify
python3 scripts/check-test-reports.py
cd exchange-web
npm ci --no-audit --no-fund
npm run build
```

This requires Java 21, Maven 3.9+, Node/npm and a suitable PostgreSQL integration-test database configuration on the host. GitHub Actions configures these dependencies for automated tests.

## Logs and safe shutdown

```bash
docker compose logs --tail=150 api
docker compose logs --tail=150 web
docker compose down
```

**Do not run `docker compose down -v`, `docker volume prune`, or delete Docker volumes to troubleshoot startup.** Those operations could destroy the persistent PostgreSQL database and monitoring state. Do not rotate the JWT key or database password in an existing `.env` casually.

If a Docker Maven build reports HTTP `429 Too Many Requests` from Maven Central, retry after the rate limit subsides; this is not proof that the pinned Spring Boot version is invalid.

## Security and deployment boundary

This Compose configuration is a **local-only simulator** setup, not a public production trading deployment. A public demo needs separate review of HTTPS/WSS, authentication, secrets, rate limits, external access, monitoring and backups. See [operations runbook](docs/operations/RELEASE_7C.md) and [release verification](docs/releases/v1.0.0-rc1-verification.md).

Never perform destructive PostgreSQL outage tests against your existing live data. Use a separate uniquely named disposable Compose project with dedicated credentials and volumes.
