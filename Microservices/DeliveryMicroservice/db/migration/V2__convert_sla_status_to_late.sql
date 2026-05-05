-- V2__convert_sla_status_to_late.sql
-- Convert legacy SLA enum values AT_RISK / BREACHED to LATE
-- Place this in your DB migration folder (e.g. Flyway `resources/db/migration`) and run before deploying the backend changes.

BEGIN;

UPDATE route_stops
SET sla_status = 'LATE'
WHERE sla_status IN ('AT_RISK', 'BREACHED');

-- If other tables store sla_status as a string enum, add similar UPDATE statements here.

COMMIT;
