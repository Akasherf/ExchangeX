#!/usr/bin/env bash
# Online, transactionally consistent PostgreSQL custom-format backup.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ $# -gt 1 ]]; then
  echo "Usage: bash scripts/backup-postgres.sh [backup-directory]" >&2; exit 2
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: Docker Compose is required." >&2; exit 1
fi
DIR="${1:-$ROOT/backups}"
mkdir -p -m 700 -- "$DIR"
DIR="$(cd "$DIR" && pwd -P)"
# Never accidentally place backups into source control.
if [[ "$DIR" != "$ROOT/backups" ]]; then
  echo "WARNING: custom backup directory; restrict access and exclude it from Git." >&2
fi
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RANDOM_TAG="$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
ARCHIVE="$DIR/exchangex_${STAMP}_${RANDOM_TAG}.dump"
TMP="$(mktemp "$DIR/.exchangex_backup.XXXXXXXX")"
trap 'rm -f -- "$TMP"' EXIT

# Dump is streamed to the HOST; never copied into the database data volume.
# pg_dump takes a consistent snapshot without interrupting normal writes.
docker compose exec -T db sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-acl' \
  > "$TMP"
if [[ ! -s "$TMP" ]]; then echo 'ERROR: Empty PostgreSQL dump.' >&2; exit 1; fi
# A readable TOC is necessary, but the full restore drill checks data integrity.
docker compose exec -T db pg_restore --list < "$TMP" > /dev/null
chmod 600 "$TMP"
if [[ -e "$ARCHIVE" || -e "$ARCHIVE.sha256" ]]; then
  echo "ERROR: Backup destination already exists." >&2; exit 1
fi
mv -- "$TMP" "$ARCHIVE"
if [[ ! -f "$ARCHIVE" ]]; then echo "ERROR: Backup destination already exists." >&2; exit 1; fi
trap - EXIT
python3 - "$ARCHIVE" <<'PY'
from pathlib import Path
import hashlib,sys
p=Path(sys.argv[1]); h=hashlib.sha256()
with p.open('rb') as f:
    for chunk in iter(lambda:f.read(1024*1024),b''):
        h.update(chunk)
manifest=Path(str(p)+'.sha256')
manifest.write_text(f'{h.hexdigest()}  {p.name}\n',encoding='ascii')
manifest.chmod(0o600)
PY
printf 'Backup written: %s\n' "$ARCHIVE"
printf 'SHA-256 manifest: %s.sha256\n' "$ARCHIVE"
