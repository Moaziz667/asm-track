-- ─────────────────────────────────────────────────────────────────────────────
-- Delivery Service — Database Schema
-- PostgreSQL 16+   (uses gen_random_uuid() built-in, no extension needed)
-- All CREATE statements use IF NOT EXISTS to be idempotent on restart.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Drivers table removed — driver data is now owned by Driver Service ────────
-- DROP TABLE IF EXISTS drivers;   ← run manually if migrating existing data
-- DROP TABLE IF EXISTS driver_otp; ← run manually if migrating existing data

-- Legacy placeholder kept for existing databases — schema won't re-create it
-- but we do not drop it automatically to preserve any existing data.
-- New deployments will simply not have this table.

-- ── Drivers (LEGACY — kept for reference only, not used by Delivery Service) ─
CREATE TABLE IF NOT EXISTS drivers (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name              VARCHAR(100) NOT NULL,
  phone             VARCHAR(20) UNIQUE NOT NULL,
  password_hash     VARCHAR(255) NOT NULL,
  available         BOOLEAN NOT NULL DEFAULT true,
  current_lat       NUMERIC(10,7),
  current_lng       NUMERIC(10,7),
  last_location_at  TIMESTAMP,
  created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
  city              VARCHAR(100)
);

-- ── Driver OTP ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS driver_otp (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  phone       VARCHAR(20) NOT NULL,
  code        VARCHAR(6) NOT NULL,
  expires_at  TIMESTAMP NOT NULL,
  used        BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ── Orders ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS orders (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Source tracking
  source                VARCHAR(10) NOT NULL CHECK (source IN ('APP', 'ODOO')),
  schema_version        VARCHAR(10) NOT NULL DEFAULT '1.0.0',

  -- Client info
  client_id             VARCHAR(100),
  client_name           VARCHAR(100) NOT NULL,
  client_phone          VARCHAR(20),
  client_email          VARCHAR(100),

  -- ERP reference (null for app orders)
  erp_order_id          VARCHAR(100) UNIQUE,
  erp_external_ref      VARCHAR(100),

  -- Origin
  origin_name           VARCHAR(100),
  origin_address        TEXT,
  origin_city           VARCHAR(100),
  origin_postal_code    VARCHAR(20),
  origin_country_code   VARCHAR(2),
  origin_contact_name   VARCHAR(100),
  origin_contact_phone  VARCHAR(20),
  origin_contact_email  VARCHAR(100),

  -- Destination
  dropoff_address       TEXT NOT NULL,
  dropoff_city          VARCHAR(100),
  dropoff_postal_code   VARCHAR(20),
  dropoff_country_code  VARCHAR(2) DEFAULT 'TN',
  dropoff_lat           NUMERIC(10,7),
  dropoff_lng           NUMERIC(10,7),
  delivery_instructions TEXT,

  -- Financial
  total_amount          NUMERIC(10,3) NOT NULL,
  currency              VARCHAR(3) NOT NULL DEFAULT 'TND',
  payment_type          VARCHAR(10) NULL,
  amount_to_collect     NUMERIC(10,3) NOT NULL DEFAULT 0,

  -- Planning
  scheduled_at          TIMESTAMP,
  priority              VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH')),

  -- Items (JSONB array)
  items                 JSONB NOT NULL,
  total_quantity        INTEGER NOT NULL DEFAULT 0,
  total_weight_kg       NUMERIC(10,3) NOT NULL DEFAULT 0,

  -- Status
  status                VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'PARTIALLY_DELIVERED', 'CANCELLED')),

  -- Metadata
  last_synced_at        TIMESTAMP,

  created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_client_id   ON orders(client_id);
CREATE INDEX IF NOT EXISTS idx_orders_status       ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_source       ON orders(source);
CREATE INDEX IF NOT EXISTS idx_orders_erp_order_id ON orders(erp_order_id);
CREATE INDEX IF NOT EXISTS idx_orders_created_at   ON orders(created_at DESC);

-- ── Deliveries ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS deliveries (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id        UUID NOT NULL UNIQUE REFERENCES orders(id),
  driver_id       UUID,

  status          VARCHAR(20) NOT NULL DEFAULT 'WAITING_DRIVER' CHECK (status IN (
    'WAITING_DRIVER',
    'ASSIGNED',
    'PICKED_UP',
    'IN_TRANSIT',
    'DELIVERED',
    'PARTIALLY_DELIVERED',
    'FAILED',
    'CANCELLED'
  )),

  assigned_at     TIMESTAMP,
  picked_up_at    TIMESTAMP,
  in_transit_at   TIMESTAMP,
  route_geometry  TEXT,
  route_distance_km NUMERIC(10,3),
  route_duration_minutes INTEGER,
  route_eta_at    TIMESTAMP,
  transit_sla_minutes_computed INTEGER,
  route_last_computed_at TIMESTAMP,
  route_provider  VARCHAR(20),
  completed_at    TIMESTAMP,
  failed_at       TIMESTAMP,
  cancelled_at    TIMESTAMP,

  fail_reason     TEXT,
  cancel_reason   TEXT,
  cancelled_by    VARCHAR(10) CHECK (cancelled_by IN ('CLIENT', 'DRIVER', 'SYSTEM')),

  created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_deliveries_driver_id ON deliveries(driver_id);
CREATE INDEX IF NOT EXISTS idx_deliveries_status    ON deliveries(status);

-- For existing databases created before the UNIQUE definition above.
CREATE UNIQUE INDEX IF NOT EXISTS uq_deliveries_order_id ON deliveries(order_id);

-- ── Delivery Status History ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS delivery_status_history (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id     UUID NOT NULL REFERENCES deliveries(id),
  status          VARCHAR(20) NOT NULL CHECK (status IN (
    'WAITING_DRIVER',
    'ASSIGNED',
    'PICKED_UP',
    'IN_TRANSIT',
    'DELIVERED',
    'PARTIALLY_DELIVERED',
    'FAILED',
    'CANCELLED'
  )),
  changed_by      VARCHAR(100),
  changed_by_role VARCHAR(10) CHECK (changed_by_role IN ('CLIENT', 'DRIVER', 'DISPATCHER', 'MANAGER', 'ADMIN', 'SYSTEM')),
  note            TEXT,
  changed_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_history_delivery_id ON delivery_status_history(delivery_id);

-- Status constraints for existing databases
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_status_check') THEN
    ALTER TABLE orders DROP CONSTRAINT orders_status_check;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_status') THEN
    ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
  END IF;
  ALTER TABLE orders ADD CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING', 'DELIVERED', 'PARTIALLY_DELIVERED', 'CANCELLED'));

  -- Migrate legacy PARTIAL values to PARTIALLY_DELIVERED before recreating constraints
  UPDATE deliveries SET status = 'PARTIALLY_DELIVERED' WHERE status = 'PARTIAL';
  UPDATE delivery_status_history SET status = 'PARTIALLY_DELIVERED' WHERE status = 'PARTIAL';

  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'deliveries_status_check') THEN
    ALTER TABLE deliveries DROP CONSTRAINT deliveries_status_check;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_deliveries_status') THEN
    ALTER TABLE deliveries DROP CONSTRAINT ck_deliveries_status;
  END IF;
  ALTER TABLE deliveries ADD CONSTRAINT ck_deliveries_status
    CHECK (status IN (
      'WAITING_DRIVER',
      'ASSIGNED',
      'PICKED_UP',
      'IN_TRANSIT',
      'DELIVERED',
      'PARTIALLY_DELIVERED',
      'FAILED',
      'CANCELLED'
    ));

  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'delivery_status_history_status_check') THEN
    ALTER TABLE delivery_status_history DROP CONSTRAINT delivery_status_history_status_check;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_delivery_history_status') THEN
    ALTER TABLE delivery_status_history DROP CONSTRAINT ck_delivery_history_status;
  END IF;
  ALTER TABLE delivery_status_history ADD CONSTRAINT ck_delivery_history_status
    CHECK (status IN (
      'WAITING_DRIVER',
      'ASSIGNED',
      'PICKED_UP',
      'IN_TRANSIT',
      'DELIVERED',
      'PARTIALLY_DELIVERED',
      'FAILED',
      'CANCELLED'
    ));

  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'delivery_status_history_changed_by_role_check') THEN
    ALTER TABLE delivery_status_history DROP CONSTRAINT delivery_status_history_changed_by_role_check;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_delivery_history_changed_by_role') THEN
    ALTER TABLE delivery_status_history DROP CONSTRAINT ck_delivery_history_changed_by_role;
  END IF;
  ALTER TABLE delivery_status_history ADD CONSTRAINT ck_delivery_history_changed_by_role
    CHECK (changed_by_role IN ('CLIENT', 'DRIVER', 'DISPATCHER', 'MANAGER', 'ADMIN', 'SYSTEM'));
END $$;

-- ── Tracking ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tracking (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id UUID NOT NULL REFERENCES deliveries(id),
  lat         NUMERIC(10,7) NOT NULL,
  lng         NUMERIC(10,7) NOT NULL,
  timestamp   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tracking_delivery_id ON tracking(delivery_id);
CREATE INDEX IF NOT EXISTS idx_tracking_timestamp   ON tracking(timestamp DESC);

-- ── Delivery Reports ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS delivery_reports (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id UUID NOT NULL REFERENCES deliveries(id),
  driver_id   UUID NOT NULL,
  report_type VARCHAR(30) NOT NULL CHECK (report_type IN (
    'ADDRESS_NOT_FOUND',
    'CUSTOMER_UNREACHABLE',
    'CUSTOMER_REFUSED',
    'DAMAGED_PACKAGE',
    'PAYMENT_ISSUE',
    'OTHER'
  )),
  description TEXT,
  created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Add city column to drivers table
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS city VARCHAR(100);

-- Add Odoo integration columns to orders table
ALTER TABLE orders ADD COLUMN IF NOT EXISTS odoo_sync_status VARCHAR(20) DEFAULT 'SYNCED';
ALTER TABLE orders ALTER COLUMN odoo_sync_status TYPE VARCHAR(40);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS odoo_backorder_id INTEGER;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_name = 'orders' AND column_name = 'client_odoo_partner_id'
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_name = 'orders' AND column_name = 'erp_client_id'
  ) THEN
    ALTER TABLE orders RENAME COLUMN client_odoo_partner_id TO erp_client_id;
  END IF;
END $$;

ALTER TABLE orders ADD COLUMN IF NOT EXISTS erp_client_id VARCHAR(100);

-- Add failure code column to deliveries table
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS failure_code VARCHAR(30);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS route_geometry TEXT;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS route_distance_km NUMERIC(10,3);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS route_duration_minutes INTEGER;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS route_eta_at TIMESTAMP;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS transit_sla_minutes_computed INTEGER;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS route_last_computed_at TIMESTAMP;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS route_provider VARCHAR(20);

-- Link delivery to route stop (tournee execution context)
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS route_stop_id UUID;

-- ── Vehicles ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS vehicles (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name       VARCHAR(100) NOT NULL,
  make       VARCHAR(60),
  model      VARCHAR(60),
  manufacture_year INTEGER,
  color      VARCHAR(40),
  vin        VARCHAR(40),
  fuel_type  VARCHAR(30),
  payload_kg INTEGER,
  volume_m3  DOUBLE PRECISION,
  mileage_km INTEGER,
  image_url  VARCHAR(500),
  plate      VARCHAR(40) NOT NULL UNIQUE,
  type       VARCHAR(20) NOT NULL CHECK (type IN ('TRUCK', 'VAN', 'CAR', 'MOTO')),
  driver_id  UUID,
  active     BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS make VARCHAR(60);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS model VARCHAR(60);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS manufacture_year INTEGER;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS color VARCHAR(40);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS vin VARCHAR(40);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS fuel_type VARCHAR(30);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS payload_kg INTEGER;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS volume_m3 DOUBLE PRECISION;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS mileage_km INTEGER;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_vehicles_driver_id ON vehicles(driver_id);

-- ── Routes / Tournees ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS routes (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name         VARCHAR(150) NOT NULL,
  driver_id    UUID NOT NULL,
  vehicle_id   UUID REFERENCES vehicles(id),
  date         DATE NOT NULL,
  planned_start_time TIME NOT NULL DEFAULT TIME '08:00',
  planned_end_time   TIME NOT NULL DEFAULT TIME '18:00',
  city         VARCHAR(100),
  status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VALIDATED', 'IN_PROGRESS', 'CLOSED')),
  created_by   VARCHAR(100) NOT NULL,
  created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
  validated_at TIMESTAMP,
  closed_at    TIMESTAMP,
  updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_routes_driver_date ON routes(driver_id, date);
CREATE INDEX IF NOT EXISTS idx_routes_status ON routes(status);
ALTER TABLE routes ADD COLUMN IF NOT EXISTS planned_start_time TIME NOT NULL DEFAULT TIME '08:00';
ALTER TABLE routes ADD COLUMN IF NOT EXISTS planned_end_time TIME NOT NULL DEFAULT TIME '18:00';
ALTER TABLE routes ADD COLUMN IF NOT EXISTS city VARCHAR(100);
ALTER TABLE routes DROP COLUMN IF EXISTS zone;

CREATE TABLE IF NOT EXISTS route_stops (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  route_id     UUID NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
  delivery_id  UUID NOT NULL UNIQUE REFERENCES deliveries(id),
  stop_order   INTEGER NOT NULL,
  status       VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ARRIVED', 'COMPLETED', 'FAILED', 'PARTIAL')),
  arrived_at   TIMESTAMP,
  completed_at TIMESTAMP,
  notes        TEXT,
  created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_route_stops_route_id_order ON route_stops(route_id, stop_order);

-- ── Proof of Delivery ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS proof_of_delivery (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id      UUID NOT NULL UNIQUE REFERENCES deliveries(id),
  photo_base64     TEXT,
  signature_base64 TEXT NOT NULL,
  comment          TEXT,
  collected_at     TIMESTAMP NOT NULL DEFAULT NOW(),
  lat              NUMERIC(10,7),
  lng              NUMERIC(10,7)
);

CREATE INDEX IF NOT EXISTS idx_pod_delivery_id ON proof_of_delivery(delivery_id);

-- MinIO URL columns for proof of delivery
ALTER TABLE proof_of_delivery ADD COLUMN IF NOT EXISTS signature_url VARCHAR(500);
ALTER TABLE proof_of_delivery ADD COLUMN IF NOT EXISTS photo_url VARCHAR(500);

-- Remove base64 columns — files are stored in MinIO, only URLs are kept
ALTER TABLE proof_of_delivery DROP COLUMN IF EXISTS signature_base64;
ALTER TABLE proof_of_delivery DROP COLUMN IF EXISTS photo_base64;

-- POD reform: bon de livraison photo + make signature_url optional
ALTER TABLE proof_of_delivery ADD COLUMN IF NOT EXISTS bon_livraison_photo_url VARCHAR(500);
ALTER TABLE proof_of_delivery ALTER COLUMN signature_url DROP NOT NULL;

-- ── Depots ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS depots (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name       VARCHAR(150) NOT NULL,
  address    TEXT,
  latitude   DOUBLE PRECISION NOT NULL,
  longitude  DOUBLE PRECISION NOT NULL,
  is_active  BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_depots_is_active ON depots(is_active);

-- ── Route alerts ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS route_alerts (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  route_id     UUID NOT NULL,
  stop_id      UUID,
  stop_order   INTEGER,
  alert_type   VARCHAR(20) NOT NULL CHECK (alert_type IN ('APPROACHING', 'AT_RISK', 'BREACHED')),
  message      TEXT NOT NULL,
  acknowledged BOOLEAN NOT NULL DEFAULT false,
  created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_route_alerts_route_id ON route_alerts(route_id);
CREATE INDEX IF NOT EXISTS idx_route_alerts_unacked  ON route_alerts(route_id, acknowledged) WHERE acknowledged = false;

-- ── Route — add depot and optimization columns ────────────────────────────────
ALTER TABLE routes ADD COLUMN IF NOT EXISTS depot_id              UUID REFERENCES depots(id);
ALTER TABLE routes ADD COLUMN IF NOT EXISTS departure_time        TIMESTAMP;
ALTER TABLE routes ADD COLUMN IF NOT EXISTS total_duration_seconds INTEGER;
ALTER TABLE routes ADD COLUMN IF NOT EXISTS total_distance_meters  INTEGER;
ALTER TABLE routes ADD COLUMN IF NOT EXISTS is_optimized           BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE routes ADD COLUMN IF NOT EXISTS route_geometry         TEXT;

-- ── Route stops — add ETA / SLA columns ──────────────────────────────────────
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS eta_at                TIMESTAMP;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS sla_deadline          TIMESTAMP;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS actual_arrival_at     TIMESTAMP;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS sla_status            VARCHAR(20) CHECK (sla_status IN ('ON_TIME', 'AT_RISK', 'BREACHED'));
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS drive_duration_seconds INTEGER;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS drive_distance_meters  INTEGER;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS dwell_minutes          INTEGER NOT NULL DEFAULT 10;

-- Fix route_stops status check to include PARTIAL (legacy) alongside COMPLETED/FAILED
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'route_stops_status_check') THEN
    ALTER TABLE route_stops DROP CONSTRAINT route_stops_status_check;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_route_stops_status') THEN
    ALTER TABLE route_stops DROP CONSTRAINT ck_route_stops_status;
  END IF;
  ALTER TABLE route_stops ADD CONSTRAINT ck_route_stops_status
    CHECK (status IN ('PENDING', 'ARRIVED', 'COMPLETED', 'FAILED', 'PARTIAL'));
END $$;

CREATE INDEX IF NOT EXISTS idx_route_stops_eta ON route_stops(eta_at) WHERE eta_at IS NOT NULL;

-- Per-leg OSRM geometry for map display
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS route_geometry TEXT;

-- ── Zones ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS zones (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name         VARCHAR(150) NOT NULL,
  color        VARCHAR(7),
  description  TEXT,
  cities       JSONB NOT NULL DEFAULT '[]',
  postal_codes JSONB NOT NULL DEFAULT '[]',
  is_active    BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_zones_is_active ON zones(is_active);

-- ── Link orders and routes to zones ──────────────────────────────────────────
ALTER TABLE orders ADD COLUMN IF NOT EXISTS zone_id UUID REFERENCES zones(id);
ALTER TABLE routes ADD COLUMN IF NOT EXISTS zone_id UUID REFERENCES zones(id);

-- ── Route execution timestamps ────────────────────────────────────────────────
ALTER TABLE routes ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
