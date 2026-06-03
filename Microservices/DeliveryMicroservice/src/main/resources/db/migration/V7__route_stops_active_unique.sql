-- ─────────────────────────────────────────────────────────────────────────────
-- Fix: align the route_stops delivery uniqueness with the application's notion of
-- an "active" stop.
--
-- Removed stops are soft-deleted (status REMOVED_REPLANNED / REMOVED_CANCELLED) but
-- intentionally keep their delivery_id for history/reporting. The old partial unique
-- index (V4) only excluded NULL delivery_id, so a soft-removed row still occupied the
-- delivery_id slot. Re-adding/replanning that delivery onto another route then failed
-- with: duplicate key value violates unique constraint "uq_route_stops_delivery_id".
--
-- New index additionally excludes removed statuses: a delivery may have many historical
-- removed rows but at most one ACTIVE row. This matches every repository query, which
-- filters status NOT IN ('REMOVED_REPLANNED','REMOVED_CANCELLED').
-- ─────────────────────────────────────────────────────────────────────────────

DROP INDEX IF EXISTS uq_route_stops_delivery_id;

CREATE UNIQUE INDEX uq_route_stops_delivery_id
    ON route_stops(delivery_id)
    WHERE delivery_id IS NOT NULL
      AND status NOT IN ('REMOVED_REPLANNED', 'REMOVED_CANCELLED');
