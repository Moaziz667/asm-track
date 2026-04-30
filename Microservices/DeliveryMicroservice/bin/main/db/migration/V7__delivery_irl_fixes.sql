-- V7: Real-world delivery logic fixes
-- Adds route_version, return_to_origin, and migrates REMOVED stop status to explicit values.

ALTER TABLE routes
    ADD COLUMN IF NOT EXISTS route_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS return_to_origin BOOLEAN NOT NULL DEFAULT FALSE;

-- Migrate existing REMOVED stops to REMOVED_CANCELLED (safe conservative default).
-- Stops that were truly replanned can be corrected manually if needed.
UPDATE route_stops
SET status = 'REMOVED_CANCELLED'
WHERE status = 'REMOVED';
