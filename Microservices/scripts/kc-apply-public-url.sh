#!/usr/bin/env bash
# Apply the env's public web origin to the Keycloak `admin-web` client (redirectUris + webOrigins).
# Keycloak realm-import can't read env vars, so this is the per-env step: run it after the stack is up
# whenever PUBLIC_URL changes (local localhost is already covered by the seed realm).
#
#   PUBLIC_URL=https://dev.asmtechtn.com bash scripts/kc-apply-public-url.sh
# or it reads PUBLIC_URL + KC_ADMIN_PASSWORD from Microservices/.env automatically.
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$HERE/.env" ] && set -a && . "$HERE/.env" && set +a

PUBLIC_URL="${PUBLIC_URL:-http://localhost:5173}"
KCPW="${KC_ADMIN_PASSWORD:?set KC_ADMIN_PASSWORD (env or .env)}"
PUBLIC_URL="${PUBLIC_URL%/}"   # strip trailing slash

echo "Applying admin-web redirect/origins for: $PUBLIC_URL (+ localhost kept for local dev)"
docker exec -e PW="$KCPW" -e URL="$PUBLIC_URL" keycloak bash -c '
  K=/opt/keycloak/bin/kcadm.sh
  $K config credentials --server http://localhost:8080 --realm master --user admin --password "$PW" >/dev/null
  CID=$($K get clients -r asm -q clientId=admin-web --fields id | grep -o "\"id\" : \"[^\"]*\"" | head -1 | sed "s/.*: \"//;s/\"//")
  [ -z "$CID" ] && { echo "admin-web client not found"; exit 1; }
  $K update clients/$CID -r asm \
    -s "redirectUris=[\"$URL/*\",\"http://localhost:5173/*\",\"http://localhost/*\"]" \
    -s "webOrigins=[\"$URL\",\"http://localhost:5173\",\"http://localhost\"]"
  echo "admin-web updated."
'
