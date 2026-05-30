#!/bin/bash
# =============================================================================
#  ASM Track — Full Reset & Demo Seed Script
#  Usage:   bash scripts/reset-and-seed.sh
#  Run from: Microservices/ directory (where docker-compose.yml lives)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$(dirname "$SCRIPT_DIR")"

RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; RESET='\033[0m'

echo ""
echo -e "${CYAN}████████████████████████████████████████████"
echo "   ASM Track — Reset & Demo Seed"
echo -e "████████████████████████████████████████████${RESET}"
echo ""
echo -e "${YELLOW}WARNING: This will permanently DELETE ALL DATA in:${RESET}"
echo "  • postgres-app      (admin users, clients)"
echo "  • postgres-delivery (companies, routes, orders, vehicles, etc.)"
echo "  • postgres-driver   (drivers, stats)"
echo ""
read -p "Continue? [y/N] " -n 1 -r; echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."; exit 0
fi

cd "$COMPOSE_DIR"

# ─── 1. Stop app services (leave DBs running) ─────────────────────────────
echo ""
echo -e "${CYAN}[1/7] Stopping application services...${RESET}"
docker compose stop app-backend delivery-service driver-service api-gateway erp-adapter 2>/dev/null || true

# ─── 2. Wipe delivery_db ──────────────────────────────────────────────────
echo -e "${CYAN}[2/7] Wiping delivery_db...${RESET}"
docker exec postgres-delivery psql -U delivery -d delivery_db << 'WIPEDELIVERY'
SET session_replication_role = 'replica';
TRUNCATE TABLE
  audit_logs,
  route_stops,
  deliveries,
  routes,
  orders,
  vehicle_inspections,
  vehicles,
  zones,
  depots,
  system_settings,
  companies
CASCADE;
SET session_replication_role = 'origin';
WIPEDELIVERY

# ─── 3. Wipe app_db ───────────────────────────────────────────────────────
echo -e "${CYAN}[3/7] Wiping app_db...${RESET}"
docker exec postgres-app psql -U app_user -d app_db << 'WIPEAPP'
SET session_replication_role = 'replica';
TRUNCATE TABLE admin_users, clients, client_otp CASCADE;
SET session_replication_role = 'origin';
WIPEAPP

# ─── 4. Wipe driver_db ────────────────────────────────────────────────────
echo -e "${CYAN}[4/7] Wiping driver_db...${RESET}"
docker exec postgres-driver psql -U driver -d driver_db << 'WIPEDRIVER'
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'driver_otps')   THEN DELETE FROM driver_otps;   END IF;
  IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'driver_history') THEN DELETE FROM driver_history; END IF;
  IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'driver_stats')   THEN DELETE FROM driver_stats;   END IF;
  DELETE FROM drivers;
END $$;
WIPEDRIVER

# ─── 5. Compute BCrypt hashes ─────────────────────────────────────────────
echo -e "${CYAN}[5/7] Computing BCrypt hashes (pulls python:3.11-alpine, ~30s)...${RESET}"

HASHES=$(docker run --rm python:3.11-alpine sh -c "
pip install bcrypt -q 2>/dev/null
python3 << 'PYEOF'
import bcrypt
for p in ['SuperAdmin2026!', 'Admin2026!', 'Dispatch2026!', 'Driver2026!']:
    print(bcrypt.hashpw(p.encode(), bcrypt.gensalt(10)).decode())
PYEOF
")

HASH_SUPER=$(echo "$HASHES" | sed -n '1p')
HASH_ADMIN=$(echo "$HASHES" | sed -n '2p')
HASH_DISP=$(echo  "$HASHES" | sed -n '3p')
HASH_DRV=$(echo   "$HASHES" | sed -n '4p')
echo "  Hashes computed"

# ─── 6. Seed databases ────────────────────────────────────────────────────
echo -e "${CYAN}[6/7] Inserting demo data...${RESET}"

# ── delivery_db ─────────────────────────────────────────────────────────────
docker exec -i postgres-delivery psql -U delivery -d delivery_db << DLSQL

-- ── Companies ──────────────────────────────────────────────────────────────
INSERT INTO companies (id, name, primary_color, address, support_email, erp_type, active, created_at)
VALUES
  ('11111111-1111-1111-1111-111111111111',
   'Rapide Express TN', '#1E40AF',
   '12 Rue de la République, Tunis 1000',
   'contact@rapide-express.tn', 'ODOO', true, NOW()),
  ('22222222-2222-2222-2222-222222222222',
   'Flash Livraison', '#059669',
   '45 Avenue Habib Bourguiba, Sfax 3000',
   'contact@flash-livraison.tn', 'ODOO', true, NOW());

-- ── Depots ─────────────────────────────────────────────────────────────────
INSERT INTO depots (id, company_id, name, address, latitude, longitude, is_active, created_at, updated_at)
VALUES
  ('d1111111-1111-1111-1111-111111111111',
   '11111111-1111-1111-1111-111111111111',
   'Dépôt Central Tunis', '12 Rue de la République, Tunis',
   36.8189, 10.1658, true, NOW(), NOW()),
  ('d2222222-2222-2222-2222-222222222222',
   '22222222-2222-2222-2222-222222222222',
   'Dépôt Sfax', '45 Avenue Habib Bourguiba, Sfax',
   34.7400, 10.7600, true, NOW(), NOW());

-- ── Zones ──────────────────────────────────────────────────────────────────
INSERT INTO zones (id, company_id, name, cities, postal_codes, is_active, created_at, updated_at)
VALUES
  ('z1111111-1111-1111-1111-111111111111',
   '11111111-1111-1111-1111-111111111111',
   'Zone Nord', '["Tunis","La Marsa","Ariana"]'::jsonb, '["1000","2070","2080"]'::jsonb,
   true, NOW(), NOW()),
  ('z1111111-1111-1111-1111-111111111112',
   '11111111-1111-1111-1111-111111111111',
   'Zone Sud Tunis', '["Ben Arous","Hammam Lif"]'::jsonb, '["2013","2050"]'::jsonb,
   true, NOW(), NOW()),
  ('z2222222-2222-2222-2222-222222222221',
   '22222222-2222-2222-2222-222222222222',
   'Zone Centre Sfax', '["Sfax","Sakiet Ezzit"]'::jsonb, '["3000","3021"]'::jsonb,
   true, NOW(), NOW()),
  ('z2222222-2222-2222-2222-222222222222',
   '22222222-2222-2222-2222-222222222222',
   'Zone Côtière', '["Mahdia","El Djem"]'::jsonb, '["5100","5160"]'::jsonb,
   true, NOW(), NOW());

-- ── Vehicles (ASM-owned fleet, no company) ─────────────────────────────────
INSERT INTO vehicles (id, name, make, model, plate, type, fuel_type, payload_kg, active, vehicle_status, created_at, updated_at)
VALUES
  ('v1000000-0000-0000-0000-000000000001', 'Master Blanc',   'Renault',   'Master',    'TU-100-AB', 'VAN',   'DIESEL', 1200, true, 'AVAILABLE',      NOW(), NOW()),
  ('v1000000-0000-0000-0000-000000000002', 'Transit Bleu',   'Ford',      'Transit',   'TU-101-AB', 'VAN',   'DIESEL', 1100, true, 'AVAILABLE',      NOW(), NOW()),
  ('v1000000-0000-0000-0000-000000000003', 'Boxer Gris',     'Peugeot',   'Boxer',     'TU-102-AB', 'VAN',   'DIESEL', 1300, true, 'AVAILABLE',      NOW(), NOW()),
  ('v1000000-0000-0000-0000-000000000004', 'Sprinter Noir',  'Mercedes',  'Sprinter',  'SF-200-CD', 'VAN',   'DIESEL', 1500, true, 'AVAILABLE',      NOW(), NOW()),
  ('v1000000-0000-0000-0000-000000000005', 'Daily Sfax',     'Iveco',     'Daily',     'SF-201-CD', 'TRUCK', 'DIESEL', 3500, true, 'AVAILABLE',      NOW(), NOW()),
  ('v1000000-0000-0000-0000-000000000006', 'Crafter Atelier','Volkswagen','Crafter',   'TU-103-AB', 'VAN',   'DIESEL', 1400, true, 'IN_MAINTENANCE', NOW(), NOW()),
  ('v1000000-0000-0000-0000-000000000007', 'Jumper Express', 'Citroën',   'Jumper',    'TU-104-AB', 'VAN',   'DIESEL', 1250, true, 'AVAILABLE',      NOW(), NOW());

-- ── System Settings (global defaults) ──────────────────────────────────────
INSERT INTO system_settings (setting_key, setting_value, updated_at)
VALUES
  ('ops.sla.waiting-limit-minutes',  '15', NOW()),
  ('ops.sla.transit-limit-minutes',  '60', NOW())
ON CONFLICT (setting_key) DO NOTHING;

DLSQL

# ── app_db ──────────────────────────────────────────────────────────────────
docker exec -i postgres-app psql -U app_user -d app_db << APPSQL



-- Company 1 — Rapide Express TN
INSERT INTO admin_users (id, name, email, password_hash, role, company_id, active, created_at)
VALUES
  (gen_random_uuid(), 'Admin Rapide Express',      'admin@rapide-express.tn',
   '$HASH_ADMIN', 'ADMIN',      '11111111-1111-1111-1111-111111111111', true, NOW()),
  (gen_random_uuid(), 'Dispatcher Rapide Express', 'dispatch@rapide-express.tn',
   '$HASH_DISP',  'DISPATCHER', '11111111-1111-1111-1111-111111111111', true, NOW());

-- Company 2 — Flash Livraison
INSERT INTO admin_users (id, name, email, password_hash, role, company_id, active, created_at)
VALUES
  (gen_random_uuid(), 'Admin Flash Livraison',      'admin@flash-livraison.tn',
   '$HASH_ADMIN', 'ADMIN',      '22222222-2222-2222-2222-222222222222', true, NOW()),
  (gen_random_uuid(), 'Dispatcher Flash Livraison', 'dispatch@flash-livraison.tn',
   '$HASH_DISP',  'DISPATCHER', '22222222-2222-2222-2222-222222222222', true, NOW());

APPSQL

# ── driver_db ────────────────────────────────────────────────────────────────
docker exec -i postgres-driver psql -U driver -d driver_db << DRVSQL

-- Company 1 drivers
INSERT INTO drivers (id, name, phone, password_hash, active, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'Ahmed Ben Salah',  '+21698000001', '$HASH_DRV', true, NOW(), NOW()),
  (gen_random_uuid(), 'Mohamed Trabelsi', '+21698000002', '$HASH_DRV', true, NOW(), NOW()),
  (gen_random_uuid(), 'Karim Mansouri',   '+21698000003', '$HASH_DRV', true, NOW(), NOW());

-- Company 2 drivers
INSERT INTO drivers (id, name, phone, password_hash, active, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'Sami Boughedir',   '+21698000004', '$HASH_DRV', true, NOW(), NOW()),
  (gen_random_uuid(), 'Raouf Khelifi',    '+21698000005', '$HASH_DRV', true, NOW(), NOW()),
  (gen_random_uuid(), 'Yassine Zagrouba', '+21698000006', '$HASH_DRV', true, NOW(), NOW());

DRVSQL

echo "  Demo data inserted"

# ─── 7. Restart services ──────────────────────────────────────────────────
echo -e "${CYAN}[7/7] Starting application services...${RESET}"
docker compose start erp-adapter app-backend delivery-service driver-service api-gateway

echo ""
echo -e "${GREEN}████████████████████████████████████████████████████"
echo "  Done! Demo environment is ready."
echo -e "████████████████████████████████████████████████████${RESET}"
echo ""
echo -e "  Credentials: ${YELLOW}scripts/CREDENTIALS.md${RESET}"
echo ""
