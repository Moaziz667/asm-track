-- V8: Add missing columns for Route and Delivery entities
-- Ensures synchronization between JPA entities and Database schema.

-- Add parent_route_id to routes for child route tracking (e.g. replanned routes)
ALTER TABLE routes ADD COLUMN IF NOT EXISTS parent_route_id UUID;

-- Add fallback route_version if V7 didn't cover it properly
ALTER TABLE routes ADD COLUMN IF NOT EXISTS route_version INTEGER NOT NULL DEFAULT 1;

-- Add return_to_origin explicitly (user report fix)
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS return_to_origin BOOLEAN NOT NULL DEFAULT FALSE;

-- Add SLA/Window columns to route_stops
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS start_time_window TIME;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS end_time_window TIME;
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS buffer_minutes INTEGER NOT NULL DEFAULT 30;
