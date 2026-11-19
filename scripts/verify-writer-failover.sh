#!/usr/bin/env bash
# Destructive to API *availability*: RUN ONLY on disposable CI or by explicit opt-in.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ "${CI:-}" != 'true' && "${ALLOW_DISRUPTIVE_FAILOVER_TEST:-}" != '1' ]]; then
  echo 'ERROR: This test restarts the API. Set ALLOW_DISRUPTIVE_FAILOVER_TEST=1 only on disposable data.' >&2
  exit 2
fi
command -v docker >/dev/null || { echo 'ERROR: Docker required.' >&2; exit 1; }

read_generation() {
  docker compose exec -T db sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT generation FROM exchange_writer_fence WHERE id=1"'
}

before="$(read_generation)"
[[ "$before" =~ ^[0-9]+$ ]] || { echo 'ERROR: Writer generation is missing.' >&2; exit 1; }

probe="exchangex_writer_probe_$$_${RANDOM}"
cid=''
cleanup() {
  if [[ -n "$cid" ]]; then docker rm -f "$cid" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
# Compose run does not publish api ports; second app targets the SAME database.
cid="$(docker compose run -d --no-deps --name "$probe" api)"
terminated=0
for _ in {1..60}; do
  if [[ "$(docker inspect -f '{{.State.Running}}' "$cid")" == false ]]; then
    terminated=1; break
  fi
  sleep 1
done
if [[ "$terminated" -ne 1 ]]; then
  echo 'ERROR: Second writer is still running; refusing to continue.' >&2
  docker logs "$cid" >&2 || true
  exit 1
fi
logs="$(docker logs "$cid" 2>&1 || true)"
if ! grep -q 'Another ExchangeX writer already owns this PostgreSQL database' <<<"$logs"; then
  echo 'ERROR: Second API exited, but not for the expected duplicate-writer reason.' >&2
  printf '%s\n' "$logs" >&2
  exit 1
fi
printf 'PASS: second API refused exclusive writer lease.\n'
cleanup
cid=''

# IMPORTANT: never run the steps below on an active external production exchange.
docker compose stop api
docker compose up -d --wait --wait-timeout 240 api
after="$(read_generation)"
[[ "$after" =~ ^[0-9]+$ ]] || { echo 'ERROR: Writer generation after restart is invalid.' >&2; exit 1; }
if (( after <= before )); then
  printf 'ERROR: Fence generation did not advance (%s -> %s).\n' "$before" "$after" >&2
  exit 1
fi
curl --fail --silent --show-error --retry 15 --retry-delay 2 --retry-connrefused \
  http://127.0.0.1:8085/api/v1/instruments > /dev/null
printf 'PASS: writer restart reacquired lease; fence generation advanced %s -> %s.\n' "$before" "$after"
