-- ─────────────────────────────────────────────────────────────────────────────
-- Delivery Service — Database Schema
-- PostgreSQL 16+   (uses gen_random_uuid() built-in, no extension needed)
-- All CREATE statements use IF NOT EXISTS to be idempotent on restart.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Drivers ──────────────────────────────────────────────────────────────────
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
  payment_type          VARCHAR(10) NOT NULL CHECK (payment_type IN ('COD', 'PREPAID')),
  amount_to_collect     NUMERIC(10,3) NOT NULL DEFAULT 0,

  -- Planning
  scheduled_at          TIMESTAMP,
  priority              VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH')),

  -- Items (JSONB array)
  items                 JSONB NOT NULL,
  total_quantity        INTEGER NOT NULL DEFAULT 0,
  total_weight_kg       NUMERIC(10,3) NOT NULL DEFAULT 0,

  -- Status
  status                VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CANCELLED')),

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
  driver_id       UUID REFERENCES drivers(id),

  status          VARCHAR(20) NOT NULL DEFAULT 'WAITING_DRIVER' CHECK (status IN (
    'WAITING_DRIVER',
    'ASSIGNED',
    'PICKED_UP',
    'IN_TRANSIT',
    'DELIVERED',
    'FAILED',
    'CANCELLED'
  )),

  assigned_at     TIMESTAMP,
  picked_up_at    TIMESTAMP,
  in_transit_at   TIMESTAMP,
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
    'FAILED',
    'CANCELLED'
  )),
  changed_by      VARCHAR(100),
  changed_by_role VARCHAR(10) CHECK (changed_by_role IN ('CLIENT', 'DRIVER', 'SYSTEM')),
  note            TEXT,
  changed_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_history_delivery_id ON delivery_status_history(delivery_id);

-- Status constraints for existing databases
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_status'
  ) THEN
    ALTER TABLE orders ADD CONSTRAINT ck_orders_status
      CHECK (status IN ('PENDING', 'CANCELLED'));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_deliveries_status'
  ) THEN
    ALTER TABLE deliveries ADD CONSTRAINT ck_deliveries_status
      CHECK (status IN (
        'WAITING_DRIVER',
        'ASSIGNED',
        'PICKED_UP',
        'IN_TRANSIT',
        'DELIVERED',
        'FAILED',
        'CANCELLED'
      ));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_delivery_history_status'
  ) THEN
    ALTER TABLE delivery_status_history ADD CONSTRAINT ck_delivery_history_status
      CHECK (status IN (
        'WAITING_DRIVER',
        'ASSIGNED',
        'PICKED_UP',
        'IN_TRANSIT',
        'DELIVERED',
        'FAILED',
        'CANCELLED'
      ));
  END IF;
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
  driver_id   UUID NOT NULL REFERENCES drivers(id),
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
ALTER TABLE orders ADD COLUMN IF NOT EXISTS client_odoo_partner_id INTEGER;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS odoo_sync_status VARCHAR(20) DEFAULT 'SYNCED';

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
