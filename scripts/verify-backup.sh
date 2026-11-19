#!/usr/bin/env bash
# Fully restores a dump into a uniquely named disposable DB, audits it, then drops ONLY that DB.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
[[ $# -eq 1 ]] || { echo 'Usage: bash scripts/verify-backup.sh <backup.dump>' >&2; exit 2; }
ARCHIVE="$1"
[[ -f "$ARCHIVE" && -s "$ARCHIVE" ]] || { echo 'ERROR: Backup file missing or empty.' >&2; exit 2; }
SUFFIX="$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
TARGET="exchangex_restore_drill_${SUFFIX}"
CREATED=0
cleanup() {
  if [[ "$CREATED" -eq 1 && "${KEEP_RESTORE_DB:-0}" != 1 ]]; then
    # Name is script-generated, never provided by the caller.
    docker compose exec -T db sh -ec \
      'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 -v target="$1" -U "$POSTGRES_USER" -d postgres' sh "$TARGET" <<'SQL'
DROP DATABASE :"target" WITH (FORCE);
SQL
  fi
}
trap cleanup EXIT
# Restore can partially create a DB before reporting failure. Retain it if restore fails,
# so no existing DB is ever dropped by a malformed or unexpected script argument.
if ! bash scripts/restore-postgres.sh "$ARCHIVE" "$TARGET"; then
  echo "ERROR: Restore failed. If created, inspect/dispose isolated database '$TARGET' manually." >&2
  exit 1
fi
CREATED=1
printf 'Checking restored schema in %s...\n' "$TARGET"
docker compose exec -T db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -f -' sh "$TARGET" \
  < scripts/sql/verify-restored-schema.sql
printf 'Checking restored financial invariants in %s...\n' "$TARGET"
docker compose exec -T db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -f -' sh "$TARGET" \
  < scripts/sql/audit-financial-invariants.sql
printf 'PASS: archived data restored to isolated database and financial invariants verified.\n'
