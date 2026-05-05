-- Migration: replace generic REMOVED with REMOVED_REPLANNED / REMOVED_CANCELLED
DO $$
BEGIN
  -- Migrate any legacy REMOVED rows (set by older code) to REMOVED_REPLANNED as safe default
  UPDATE route_stops SET status = 'REMOVED_REPLANNED' WHERE status = 'REMOVED';

  -- Drop old constraint
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_route_stops_status') THEN
    ALTER TABLE route_stops DROP CONSTRAINT ck_route_stops_status;
  END IF;

  -- Recreate with full enum including both removal reasons
  ALTER TABLE route_stops ADD CONSTRAINT ck_route_stops_status
    CHECK (status IN (
      'PENDING',
      'SCHEDULED',
      'PICKED_UP',
      'IN_TRANSIT',
      'ARRIVED',
      'COMPLETED',
      'FAILED',
      'PARTIAL',
      'FAILED_ATTEMPT',
      'REMOVED_REPLANNED',
      'REMOVED_CANCELLED'
    ));
END $$;
