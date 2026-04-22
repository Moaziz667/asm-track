-- V10: Add stop removal audit columns to route_stops
-- Used by cancelStop() to record when/why/by whom a stop was cancelled or replanned.

ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS removed_at TIMESTAMP;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS removed_reason TEXT;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS removed_by VARCHAR(100);
