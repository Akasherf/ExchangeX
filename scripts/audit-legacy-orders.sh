#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
command -v docker >/dev/null || { echo 'ERROR: Docker required.' >&2; exit 1; }
# The database container provides its own connection configuration. SQL runs read-only.
docker compose exec -T db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -' \
  < scripts/sql/audit-legacy-orders.sql
