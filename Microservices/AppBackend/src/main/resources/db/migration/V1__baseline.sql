-- V1 — Schéma de référence du service AppBackend, par client.
--
-- Reprend mot pour mot l'ancien schema.sql, qui était exécuté par TenantSchemaProvisioner en
-- découpant le fichier sur « ; » et en avalant chaque erreur au niveau debug. Trois défauts dans
-- cette approche :
--
--   1. aucun suivi de version — modifier ce fichier ne touchait que les NOUVEAUX clients ; les
--      existants divergeaient en silence, sans moyen de le savoir ;
--   2. un CREATE TABLE en échec passait inaperçu, et l'application démarrait sur un schéma
--      incomplet ;
--   3. le découpage sur « ; » casse dès qu'un point-virgule apparaît dans une chaîne ou un corps
--      de fonction.
--
-- Flyway règle les trois. Les schémas déjà provisionnés sont adoptés via baseline-on-migrate :
-- vérifié avant la bascule, les 6 clients portaient une signature de colonnes identique
-- (98f84e7b89d4), donc cette référence décrit bien ce qu'ils contiennent.

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

-- Keycloak reconciliation dirty-flag: false = may diverge from the Keycloak mirror (just
-- created/updated). Existing rows default to false so the reconciler re-asserts them once
-- (incl. the newly-synced display name) after this migration.
ALTER TABLE admin_users ADD COLUMN IF NOT EXISTS kc_synced BOOLEAN NOT NULL DEFAULT false;

-- Transactional outbox for IAM provisioning commands (drained to Keycloak, exactly-once).
CREATE TABLE IF NOT EXISTS outbox_event (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type    VARCHAR(50)  NOT NULL,
  payload       TEXT         NOT NULL,
  status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  retry_count   INTEGER      NOT NULL DEFAULT 0,
  last_error    TEXT,
  created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
  processed_at  TIMESTAMP,
  next_retry_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_outbox_status_retry ON outbox_event(status, next_retry_at);

-- System Settings
CREATE TABLE IF NOT EXISTS system_settings (
  id                  VARCHAR(50) PRIMARY KEY,
  active_erp_provider VARCHAR(50),
  erp_configuration   TEXT,
  updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ERP connection lifecycle: 'configured' (saved, untested) is NOT 'connected' (creds verified).
-- These columns make the connection state persistent and visible across Config + Import pages.
-- connection_status: NOT_CONFIGURED | CONFIGURED | CONNECTED | ERROR
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS connection_status VARCHAR(20) NOT NULL DEFAULT 'NOT_CONFIGURED';
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS last_tested_at    TIMESTAMP;
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS last_connected_at TIMESTAMP;
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS last_error        TEXT;
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS last_test_uid     VARCHAR(50);

-- Backfill: a row that already has a provider + config existed BEFORE this column was added,
-- so it wrongly defaulted to NOT_CONFIGURED. Mark it CONFIGURED (saved, awaiting a test) — it
-- isn't proven CONNECTED, but it's certainly not unconfigured. Idempotent.
UPDATE system_settings
   SET connection_status = 'CONFIGURED'
 WHERE connection_status = 'NOT_CONFIGURED'
   AND active_erp_provider IS NOT NULL
   AND active_erp_provider <> 'NONE'
   AND erp_configuration IS NOT NULL;

-- ── Multi-tenant: company_id on existing tables ──────────────────────────────
ALTER TABLE admin_users ADD COLUMN IF NOT EXISTS company_id UUID;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS company_id UUID;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS company_id UUID;
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS company_id UUID;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_admin_users_company ON admin_users(company_id);
CREATE INDEX IF NOT EXISTS idx_clients_company ON clients(company_id);

-- ── Company ERP config (per-tenant ERP credentials) ─────────────────────────
CREATE TABLE IF NOT EXISTS company_erp_config (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL,
    erp_type        VARCHAR(20) NOT NULL DEFAULT 'ODOO',
    api_url         VARCHAR(512),
    api_key_enc     TEXT,
    db_name         VARCHAR(100),
    username        VARCHAR(100),
    uid             INT,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    last_tested_at  TIMESTAMP,
    last_error      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(company_id, erp_type)
);

-- ── Company features (feature flags per tenant) ─────────────────────────────
CREATE TABLE IF NOT EXISTS company_features (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL,
    feature_key VARCHAR(100) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    config      JSONB DEFAULT '{}',
    UNIQUE(company_id, feature_key)
);

-- ── Provisioning audit trail ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS provisioning_audit (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID,
    action      VARCHAR(50) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    details     TEXT,
    performed_by UUID,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
