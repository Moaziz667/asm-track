-- ADR-033 — Reverse-pickup deliveries. A return is modelled as a Delivery of kind RETURN_PICKUP
-- (client → depot), reusing the original order. These columns discriminate it and link it to its RMA
-- and destination depot. Existing rows are FORWARD.
ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS kind             VARCHAR(20) NOT NULL DEFAULT 'FORWARD',
    ADD COLUMN IF NOT EXISTS rma_id           UUID,
    ADD COLUMN IF NOT EXISTS return_depot_id  UUID;

-- Fast lookup of the collection leg for a given RMA (one open reverse pickup per RMA).
CREATE INDEX IF NOT EXISTS idx_deliveries_rma_id ON deliveries (rma_id) WHERE rma_id IS NOT NULL;
