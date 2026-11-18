#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
if ! command -v docker >/dev/null 2>&1; then
  echo 'ERROR: Docker is required for the Compose read-only audit.' >&2
  exit 1
fi

echo 'Auditing ExchangeX Docker database with a read-only PostgreSQL snapshot...'
# POSTGRES_USER and POSTGRES_DB come from the db container, not the host.
# No password or secret is displayed or written to disk by this script.
docker compose exec -T db sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -' \
  < scripts/sql/audit-financial-invariants.sql
