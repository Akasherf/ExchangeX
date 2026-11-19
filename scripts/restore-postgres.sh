#!/usr/bin/env bash
# Intentionally refuses to restore into an existing database or protected name.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
usage() { echo 'Usage: bash scripts/restore-postgres.sh <backup.dump> <NEW_DISPOSABLE_DB_NAME>' >&2; exit 2; }
[[ $# -eq 2 ]] || usage
ARCHIVE="$1"
TARGET="$2"
[[ -f "$ARCHIVE" && -s "$ARCHIVE" ]] || { echo 'ERROR: Backup does not exist or is empty.' >&2; exit 2; }
# Restrict untrusted input to a valid, short, non-special PostgreSQL identifier.
[[ "$TARGET" =~ ^exchangex_restore_[a-z0-9_]{4,32}$ ]] || {
  echo 'ERROR: Target must be a NEW database named exchangex_restore_<4-32 lowercase letters/digits/underscores>.' >&2; exit 2;
}
if ! command -v docker >/dev/null 2>&1; then
  echo 'ERROR: Docker Compose is required.' >&2; exit 1
fi
# Require a checksum by default; manually supplied dumps need explicit opt-in.
if [[ ! -f "$ARCHIVE.sha256" && "${ALLOW_UNVERIFIED_BACKUP:-0}" != 1 ]]; then
  echo 'ERROR: Missing SHA-256 manifest; set ALLOW_UNVERIFIED_BACKUP=1 only for independently verified archives.' >&2; exit 2
fi
if [[ -f "$ARCHIVE.sha256" ]]; then
  python3 - "$ARCHIVE" <<'PY'
import hashlib,sys,pathlib
p=pathlib.Path(sys.argv[1]); line=pathlib.Path(str(p)+'.sha256').read_text().strip().split()
if len(line)!=2 or line[1]!=p.name: raise SystemExit('ERROR: Backup SHA256 manifest is invalid.')
h=hashlib.sha256()
with p.open('rb') as f:
    for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
if h.hexdigest()!=line[0]: raise SystemExit('ERROR: Backup SHA256 mismatch.')
PY
fi
# Check archive before creating any database.
docker compose exec -T db pg_restore --list < "$ARCHIVE" > /dev/null
# Reject existing targets; never DROP, overwrite, or --clean a live database.
EXISTS="$(docker compose exec -T db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -At -v ON_ERROR_STOP=1 -v target="$1" -U "$POSTGRES_USER" -d postgres' sh "$TARGET" <<'SQL'
SELECT 1 FROM pg_database WHERE datname = :'target';
SQL
)"
[[ -z "$EXISTS" ]] || { echo "ERROR: Database '$TARGET' already exists; refusing to overwrite." >&2; exit 1; }
# PostgreSQL's identifier quoting is handled by psql's :"target" expansion.
docker compose exec -T db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 -v target="$1" -U "$POSTGRES_USER" -d postgres' sh "$TARGET" <<'SQL' > /dev/null
CREATE DATABASE :"target";
SQL
printf 'Created isolated restore target: %s\n' "$TARGET"
# --single-transaction prevents an inconsistent partial restoration on error.
docker compose exec -T db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_restore -U "$POSTGRES_USER" -d "$1" --no-owner --no-acl --exit-on-error --single-transaction' sh "$TARGET" < "$ARCHIVE"
printf 'Restore completed: %s (never expose this database through the running API).\n' "$TARGET"
