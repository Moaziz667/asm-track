CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS clients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    phone           VARCHAR(20) UNIQUE NOT NULL,
    email           VARCHAR(100),
    address         VARCHAR(255),
    phone_verified  BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_clients_phone ON clients(phone);

CREATE TABLE IF NOT EXISTS client_otp (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       VARCHAR(20) NOT NULL,
    code        VARCHAR(6) NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_client_otp_phone ON client_otp(phone);
CREATE INDEX IF NOT EXISTS idx_client_otp_created_at ON client_otp(created_at DESC);

-- Odoo integration
ALTER TABLE clients ADD COLUMN IF NOT EXISTS odoo_partner_id INTEGER;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS company_id UUID;

-- Admin / Dispatcher / Manager accounts
CREATE TABLE IF NOT EXISTS admin_users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(100) UNIQUE NOT NULL,
  role          VARCHAR(20) NOT NULL,
  active        BOOLEAN NOT NULL DEFAULT true,
  created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Link admin users to the single-tenant company profile
ALTER TABLE admin_users ADD COLUMN IF NOT EXISTS company_id UUID;

-- System Settings
CREATE TABLE IF NOT EXISTS system_settings (
  id                  VARCHAR(50) PRIMARY KEY,
  active_erp_provider VARCHAR(50),
  erp_configuration   TEXT,
  updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
