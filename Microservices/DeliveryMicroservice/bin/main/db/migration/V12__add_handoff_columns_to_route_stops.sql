-- V12: Add handoff tracking columns to route_stops
-- Supports formal package custody transfer between drivers when a PICKED_UP stop is reassigned.

ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS requires_handoff BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS handoff_from_driver_id UUID;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS handoff_to_driver_id UUID;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS handoff_confirmed_at TIMESTAMP;
