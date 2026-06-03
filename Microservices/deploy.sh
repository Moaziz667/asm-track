#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ASM Track — VPS deploy.
#
#   ./deploy.sh            redeploy: build + (re)start the stack (keeps data)
#   ./deploy.sh --seed     first run: wipe volumes, restore db-seed/ dumps,
#                          then build + start everything (incl. Odoo)
#
# Run from the Microservices/ directory. Needs Microservices/.env (gitignored)
# and, for --seed, the dumps in Microservices/db-seed/ (gitignored).
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")"

ODOO_COMPOSE="../docker-compose.odoo2.yml"
ODOO_PROJECT="pfe"          # pins Odoo volume names to pfe_odoo2_data, etc.
SEED_DIR="db-seed"

if [[ ! -f .env ]]; then
  echo "ERROR: Microservices/.env is missing (gitignored). Copy it over first."
  exit 1
fi

wait_pg() {  # wait_pg <container> <user> <db>
  echo -n "   waiting for $1 "
  until docker exec "$1" pg_isready -U "$2" -d "$3" >/dev/null 2>&1; do echo -n "."; sleep 1; done
  echo " ready"
}

if [[ "${1:-}" == "--seed" ]]; then
  echo "==> SEED MODE — this WIPES all existing data in the docker volumes."
  for f in app_db.sql delivery_db.sql driver_db.sql odoo2.dump odoo2_filestore.tar.gz; do
    [[ -f "$SEED_DIR/$f" ]] || { echo "ERROR: missing $SEED_DIR/$f"; exit 1; }
  done

  echo "==> Wiping old volumes…"
  docker compose down -v
  docker compose -f "$ODOO_COMPOSE" -p "$ODOO_PROJECT" down -v 2>/dev/null || true

  echo "==> Starting databases (creates the shared network)…"
  docker compose up -d postgres-app postgres-delivery postgres-driver
  wait_pg postgres-app      app_user app_db
  wait_pg postgres-delivery delivery delivery_db
  wait_pg postgres-driver   driver   driver_db

  echo "==> Restoring application databases…"
  docker exec -i postgres-app      psql -U app_user -d app_db      < "$SEED_DIR/app_db.sql"
  docker exec -i postgres-delivery psql -U delivery  -d delivery_db < "$SEED_DIR/delivery_db.sql"
  docker exec -i postgres-driver   psql -U driver    -d driver_db   < "$SEED_DIR/driver_db.sql"

  echo "==> Restoring Odoo database + filestore…"
  docker compose -f "$ODOO_COMPOSE" -p "$ODOO_PROJECT" up -d postgres-odoo-2
  wait_pg postgres-odoo-2 odoo2 odoo2
  docker exec -i postgres-odoo-2 pg_restore -U odoo2 -d odoo2 --no-owner --clean --if-exists \
    < "$SEED_DIR/odoo2.dump"
  docker run --rm -v "${ODOO_PROJECT}_odoo2_data:/data" -v "$(pwd)/$SEED_DIR:/backup" \
    alpine sh -c "cd /data && tar xzf /backup/odoo2_filestore.tar.gz"
fi

echo "==> Building and starting the full stack…"
docker compose up -d --build

echo "==> Starting Odoo…"
docker compose -f "$ODOO_COMPOSE" -p "$ODOO_PROJECT" up -d
docker compose restart erp-adapter

echo "==> Status:"
docker compose ps

cat <<'EOF'

==> Done.
    Admin dashboard : http://192.168.10.76:8090
    API gateway     : http://192.168.10.76        (driver app target)
    Odoo            : http://192.168.10.76:8070

    Logs:  docker compose logs -f delivery-service
    Stop:  docker compose down   (add -v to also wipe data)
EOF
