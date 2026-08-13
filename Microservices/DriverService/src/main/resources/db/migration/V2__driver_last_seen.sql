-- Presence, as opposed to movement.
--
-- Availability was decided on last_location_at alone, which answers "did he move recently" — a
-- different question. A driver parked at a customer went offline while using the app, and a driver
-- who had never sent a position was never swept at all, a NULL being older than nothing.
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

-- Existing rows: the last position is the only sighting on record, so it stands in for one.
UPDATE drivers SET last_seen_at = last_location_at WHERE last_seen_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_drivers_presence ON drivers (online_status, last_seen_at);
