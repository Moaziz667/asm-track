-- ─────────────────────────────────────────────────────────────────────────────
-- Multi-depot sourcing (Phase 2, slice 2)
-- Links an order/delivery to a SOURCE DEPOT derived from the ERP delivery-note
-- (bon de livraison) warehouse, plus a warehouse↔depot mapping table. Additive
-- and backward-compatible: existing rows keep source_depot_id = NULL (= home depot).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE orders ADD COLUMN bl_number       VARCHAR(100);
ALTER TABLE orders ADD COLUMN warehouse_code  VARCHAR(50);
ALTER TABLE orders ADD COLUMN source_depot_id UUID REFERENCES depots(id) ON DELETE SET NULL;

-- Per-delivery-note import idempotency.
CREATE INDEX idx_orders_bl_number ON orders(bl_number) WHERE bl_number IS NOT NULL;

ALTER TABLE deliveries ADD COLUMN source_depot_id UUID REFERENCES depots(id) ON DELETE SET NULL;
CREATE INDEX idx_deliveries_source_depot ON deliveries(source_depot_id) WHERE source_depot_id IS NOT NULL;

-- ERP warehouse → ASM depot mapping (keyed by warehouse code; one ERP per deployment).
CREATE TABLE warehouse_depot_mappings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_code VARCHAR(50)  NOT NULL UNIQUE,
    depot_id       UUID NOT NULL REFERENCES depots(id) ON DELETE CASCADE,
    provider       VARCHAR(20),
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);
