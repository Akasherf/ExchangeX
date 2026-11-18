#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ -e .env ]]; then
  echo 'Found existing .env; leaving credentials unchanged.'
  exit 0
fi
umask 077
cat > .env <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 24)
EXCHANGEX_JWT_SECRET_BASE64=$(openssl rand -base64 48 | tr -d '\n')
GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 24)
EOF
chmod 600 .env
echo 'Created private .env (mode 600). Do not commit it. Keep JWT and database credentials stable for this Compose volume.'
