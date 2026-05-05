-- Migration: extend route_stops.status check constraint to support route execution statuses
-- Run with psql or your DB migration tool before deploying the backend.

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'route_stops_status_check') THEN
    ALTER TABLE route_stops DROP CONSTRAINT route_stops_status_check;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_route_stops_status') THEN
    ALTER TABLE route_stops DROP CONSTRAINT ck_route_stops_status;
  END IF;
  ALTER TABLE route_stops ADD CONSTRAINT ck_route_stops_status
    CHECK (status IN (
      'PENDING',
      'ASSIGNED',
      'PICKED_UP',
      'IN_TRANSIT',
      'ARRIVED',
      'COMPLETED',
      'FAILED',
      'PARTIAL',
      'REMOVED'
    ));
END $$;
