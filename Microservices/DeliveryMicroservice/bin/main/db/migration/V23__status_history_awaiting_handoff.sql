-- ADR-031 follow-up: the delivery status timeline records the delivery's status at each event, so a
-- reassign of an in-field parcel writes a DELIVERY_STATUS_HISTORY row with status AWAITING_HANDOFF
-- (event HANDOFF_REQUESTED). Its CHECK constraint (from V1) predates the status, so the write was
-- rejected (23514). Widen it to match the deliveries constraint (V22).
-- Note: route_stops keeps its own RouteStopStatus set (no AWAITING_HANDOFF) — a transferred stop stays
-- PENDING on the target route, so that constraint intentionally does NOT change.
ALTER TABLE delivery_status_history DROP CONSTRAINT delivery_status_history_status_check;
ALTER TABLE delivery_status_history ADD CONSTRAINT delivery_status_history_status_check
    CHECK (status IN (
        'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'AWAITING_HANDOFF',
        'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'
    ));
