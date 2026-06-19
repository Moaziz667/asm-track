-- =============================================================================
-- Single-tenant cleanup + explicit applicability for the failure-reason catalog.
--   1. Drop the company scoping (the app serves one company; see V17 note).
--   2. Add `applies_to` — where each motif is offered, decoupled from the analytics
--      `category`: FAILURE | ITEM_REFUSED | ITEM_DAMAGED | ITEM_MISSING (CSV set).
--   3. Seed MISSING-category motifs for the item-missing / short-quantity case.
-- =============================================================================

-- 1. Drop company scoping --------------------------------------------------------
ALTER TABLE failure_reasons DROP CONSTRAINT IF EXISTS uq_failure_reasons_company_code;
DROP INDEX IF EXISTS idx_failure_reasons_company_active;
ALTER TABLE failure_reasons DROP COLUMN IF EXISTS company_id;

ALTER TABLE failure_reasons ADD CONSTRAINT uq_failure_reasons_code UNIQUE (code);
CREATE INDEX IF NOT EXISTS idx_failure_reasons_active ON failure_reasons(active);

-- 2. Explicit applicability ------------------------------------------------------
ALTER TABLE failure_reasons ADD COLUMN IF NOT EXISTS applies_to VARCHAR(120) NOT NULL DEFAULT 'FAILURE';

-- Backfill existing rows from their analytics category: refusal/damage reasons make
-- sense both as a full-visit failure and at the item level; the rest are full-visit only.
UPDATE failure_reasons SET applies_to = 'FAILURE,ITEM_REFUSED' WHERE category = 'REFUSED';
UPDATE failure_reasons SET applies_to = 'FAILURE,ITEM_DAMAGED' WHERE category = 'DAMAGED';

-- 3. Seed MISSING-category motifs (item-missing only). Idempotent on code.
INSERT INTO failure_reasons (code, label, category, applies_to, sort_order) VALUES
  ('MISS_STOCK',      'Rupture de stock',  'MISSING', 'ITEM_MISSING', 150),
  ('MISS_NOT_LOADED', 'Colis non chargé',  'MISSING', 'ITEM_MISSING', 160),
  ('MISS_LOST',       'Colis introuvable', 'MISSING', 'ITEM_MISSING', 170)
ON CONFLICT (code) DO NOTHING;
