-- Migration: add CANCELLED to routes status check constraint
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'routes_status_check') THEN
    ALTER TABLE routes DROP CONSTRAINT routes_status_check;
  END IF;
  ALTER TABLE routes ADD CONSTRAINT routes_status_check
    CHECK (status IN ('DRAFT', 'VALIDATED', 'IN_PROGRESS', 'CLOSED', 'CANCELLED'));
END $$;
