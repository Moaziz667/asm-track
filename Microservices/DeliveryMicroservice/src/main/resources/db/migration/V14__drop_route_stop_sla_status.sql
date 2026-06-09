-- Retire the legacy per-stop SLA status column. The unified SlaState (SlaEvaluator/
-- SlaStateService) is now the single source of SLA truth; nothing computes or reads
-- route_stops.sla_status anymore (the only writer was removed, and getSlaSummary now
-- reads SlaState). Dropping the inert column finishes the migration cleanly.
ALTER TABLE route_stops DROP COLUMN IF EXISTS sla_status;
