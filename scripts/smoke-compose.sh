#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
echo '[1/7] Compose services'
docker compose ps
# Fail before calling localhost URLs: another local process might answer on 8088.
running_services="$(docker compose ps --status running --services)"
for service in db api web prometheus grafana; do
  if ! grep -qx "$service" <<< "$running_services"; then
    echo "ERROR: ExchangeX Compose service '$service' is not running. Check 'docker compose ps' and 'docker compose logs'." >&2
    exit 1
  fi
done
echo '[2/7] Frontend readiness'
curl --fail --silent --show-error --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8088/healthz
echo '[3/7] Web UI HTML'
curl --fail --silent --show-error --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8088/ | grep -i '<html' >/dev/null
echo '[4/7] Public API through reverse proxy'
curl --fail --silent --show-error --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:8088/api/v1/instruments | grep 'AAPL' >/dev/null
echo '[5/7] Internal coordinator readiness'
# Readiness is separate from liveness/public instruments and must reflect worker status.
docker compose exec -T api curl --fail --silent --show-error --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:9090/actuator/health/readiness | grep -q '"status":"UP"'
echo '[6/7] Internal Prometheus exposition'
docker compose exec -T api curl --fail --silent --show-error --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:9090/actuator/prometheus | grep 'jvm_memory_used_bytes' >/dev/null
echo '[7/7] Prometheus & Grafana web health'
curl --fail --silent --show-error --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:9091/-/ready >/dev/null
curl --fail --silent --show-error --retry 20 --retry-delay 2 --retry-connrefused http://127.0.0.1:3000/api/health >/dev/null
echo 'PASS: Compose API, UI, coordinator readiness, reverse proxy, metrics, Prometheus and Grafana are responding.'
echo 'Next: verify live STOMP subscriptions, authenticated order lifecycle and both symbols in Grafana.'
