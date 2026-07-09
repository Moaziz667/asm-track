-- ADR-031: in-field reassign no longer rewinds a departed parcel to SCHEDULED — it gets the dedicated
-- lateral status AWAITING_HANDOFF (PICKED_UP/IN_TRANSIT -> AWAITING_HANDOFF -> PICKED_UP on confirm).
-- The deliveries status CHECK constraint (from V1) predates it, so any reassign that sets the new status
-- was rejected by Postgres (23514). Widen the constraint to include AWAITING_HANDOFF.
ALTER TABLE deliveries DROP CONSTRAINT deliveries_status_check;
ALTER TABLE deliveries ADD CONSTRAINT deliveries_status_check
    CHECK (status IN (
        'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'AWAITING_HANDOFF',
        'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'
    ));
