-- 1. Drop existing constraints to avoid violations during update
ALTER TABLE deliveries DROP CONSTRAINT IF EXISTS ck_deliveries_status;
ALTER TABLE deliveries DROP CONSTRAINT IF EXISTS deliveries_status_check;

ALTER TABLE delivery_status_history DROP CONSTRAINT IF EXISTS ck_delivery_history_status;
ALTER TABLE delivery_status_history DROP CONSTRAINT IF EXISTS delivery_status_history_status_check;

ALTER TABLE route_stops DROP CONSTRAINT IF EXISTS route_stops_status_check;

-- 2. Update existing data in main tables
UPDATE deliveries SET status = 'UNSCHEDULED' WHERE status = 'WAITING_DRIVER';
UPDATE deliveries SET status = 'SCHEDULED' WHERE status = 'ASSIGNED';

UPDATE delivery_status_history SET status = 'UNSCHEDULED' WHERE status = 'WAITING_DRIVER';
UPDATE delivery_status_history SET status = 'SCHEDULED' WHERE status = 'ASSIGNED';

UPDATE route_stops SET status = 'SCHEDULED' WHERE status = 'ASSIGNED';

-- 3. Update CHECK constraints for deliveries
ALTER TABLE deliveries ADD CONSTRAINT ck_deliveries_status 
  CHECK (status IN ('UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'));

-- 4. Update CHECK constraints for delivery_status_history
ALTER TABLE delivery_status_history ADD CONSTRAINT ck_delivery_history_status 
  CHECK (status IN ('UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'));

-- 5. Update CHECK constraints for route_stops
ALTER TABLE route_stops ADD CONSTRAINT route_stops_status_check 
  CHECK (status IN ('PENDING', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'ARRIVED', 'COMPLETED', 'FAILED', 'PARTIAL', 'REMOVED'));

-- 6. Update table defaults
ALTER TABLE deliveries ALTER COLUMN status SET DEFAULT 'UNSCHEDULED';

-- 7. Update SLA motifs in history notes (optional but good for consistency)
UPDATE delivery_status_history 
SET note = REPLACE(note, 'WAITING_DRIVER', 'UNSCHEDULED')
WHERE note LIKE '%WAITING_DRIVER%';

UPDATE delivery_status_history 
SET note = REPLACE(note, 'ASSIGNED', 'SCHEDULED')
WHERE note LIKE '%ASSIGNED%';
