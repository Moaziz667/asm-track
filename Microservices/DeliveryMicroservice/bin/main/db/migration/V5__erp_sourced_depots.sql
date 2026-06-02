-- ─────────────────────────────────────────────────────────────────────────────
-- ERP-sourced depots (Phase 2, slice 5)
-- Depots are no longer hand-created: each depot row mirrors an ERP warehouse
-- (Odoo stock.warehouse), keyed by its warehouse code. A BL's warehouse_code now
-- resolves 1:1 to its depot row, so the warehouse↔depot mapping table is dropped.
-- Coordinates become nullable (filled from Odoo or by geocoding the address).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE depots ADD COLUMN warehouse_code   VARCHAR(50);
ALTER TABLE depots ADD COLUMN erp_warehouse_id VARCHAR(50);
ALTER TABLE depots ADD COLUMN provider         VARCHAR(20);

-- An ERP warehouse may have no coordinates until geocoded.
ALTER TABLE depots ALTER COLUMN latitude  DROP NOT NULL;
ALTER TABLE depots ALTER COLUMN longitude DROP NOT NULL;

-- One depot per ERP warehouse code (upsert key for the sync).
CREATE UNIQUE INDEX uq_depots_warehouse_code ON depots(warehouse_code) WHERE warehouse_code IS NOT NULL;

-- The warehouse → depot mapping is obsolete: the warehouse IS the depot now.
DROP TABLE IF EXISTS warehouse_depot_mappings;
