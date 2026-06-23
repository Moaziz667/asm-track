-- ─────────────────────────────────────────────────────────────────────────────
-- Multi-depot routing core (Phase 2, slice 4)
-- Adds PICKUP route stops (one per non-home source depot). A PICKUP stop is not
-- tied to a single delivery, so delivery_id becomes nullable; a partial-unique
-- index keeps the 1:1 guarantee for real deliveries. Additive/backward-compatible:
-- existing rows default to stop_type = 'DELIVERY'.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE route_stops ADD COLUMN stop_type VARCHAR(20) NOT NULL DEFAULT 'DELIVERY'
    CHECK (stop_type IN ('PICKUP', 'DELIVERY'));
ALTER TABLE route_stops ADD COLUMN source_depot_id UUID REFERENCES depots(id) ON DELETE SET NULL;

-- A PICKUP stop loads a whole depot, not one delivery → delivery_id must allow NULL.
ALTER TABLE route_stops ALTER COLUMN delivery_id DROP NOT NULL;
ALTER TABLE route_stops DROP CONSTRAINT route_stops_delivery_id_key;
CREATE UNIQUE INDEX uq_route_stops_delivery_id
    ON route_stops(delivery_id) WHERE delivery_id IS NOT NULL;

-- Shape integrity: deliveries carry a delivery_id; pickups carry a source depot instead.
ALTER TABLE route_stops ADD CONSTRAINT chk_route_stops_shape CHECK (
    (stop_type = 'DELIVERY' AND delivery_id IS NOT NULL) OR
    (stop_type = 'PICKUP'   AND delivery_id IS NULL AND source_depot_id IS NOT NULL)
);
CREATE INDEX idx_route_stops_source_depot
    ON route_stops(source_depot_id) WHERE source_depot_id IS NOT NULL;
