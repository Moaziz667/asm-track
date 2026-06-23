-- ─────────────────────────────────────────────────────────────────────────────
-- Handoff aggregate: first-class custody-transfer lifecycle, replacing the
-- scattered handoff_* booleans previously held on route_stops. The legacy
-- columns are retained (kept in sync by HandoffService) and dropped in a later
-- migration once all read paths use the aggregate.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE handoffs (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id          UUID NOT NULL,
    route_id             UUID,
    route_stop_id        UUID,
    from_driver_id       UUID NOT NULL,
    to_driver_id         UUID NOT NULL,
    state                VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                            CHECK (state IN ('REQUESTED','IN_PROGRESS','CONFIRMED','EXPIRED','CANCELLED')),

    token                VARCHAR(16),
    token_expires_at     TIMESTAMP,
    token_attempts       INTEGER NOT NULL DEFAULT 0,

    requested_at         TIMESTAMP NOT NULL DEFAULT now(),
    requested_by         VARCHAR(100),
    in_progress_at       TIMESTAMP,
    confirmed_at         TIMESTAMP,
    cancelled_at         TIMESTAMP,
    cancelled_by         VARCHAR(100),
    expired_at           TIMESTAMP,
    overdue_notified_at  TIMESTAMP,
    reason               VARCHAR(500),

    confirm_lat          NUMERIC(10,7),
    confirm_lng          NUMERIC(10,7),
    evidence_url         VARCHAR(500),
    notes                TEXT,

    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_handoffs_delivery        ON handoffs(delivery_id);
CREATE INDEX idx_handoffs_to_driver_state ON handoffs(to_driver_id, state);
CREATE INDEX idx_handoffs_from_driver_state ON handoffs(from_driver_id, state);
CREATE INDEX idx_handoffs_open            ON handoffs(state) WHERE state IN ('REQUESTED','IN_PROGRESS');

-- Fast lookup of the active handoff from a stop.
ALTER TABLE route_stops ADD COLUMN active_handoff_id UUID;

-- ── Backfill from the legacy route_stops.handoff_* columns ───────────────────
WITH ins AS (
    INSERT INTO handoffs (
        id, delivery_id, route_id, route_stop_id, from_driver_id, to_driver_id,
        state, token, token_expires_at, token_attempts,
        requested_at, confirmed_at, version, created_at, updated_at)
    SELECT gen_random_uuid(), rs.delivery_id, rs.route_id, rs.id,
           rs.handoff_from_driver_id, rs.handoff_to_driver_id,
           CASE WHEN rs.handoff_confirmed_at IS NOT NULL THEN 'CONFIRMED' ELSE 'REQUESTED' END,
           rs.handoff_token, rs.handoff_token_expires_at, 0,
           COALESCE(rs.created_at, now()), rs.handoff_confirmed_at, 0, now(), now()
    FROM route_stops rs
    WHERE rs.handoff_from_driver_id IS NOT NULL
      AND rs.handoff_to_driver_id IS NOT NULL
      AND (rs.requires_handoff = TRUE OR rs.handoff_confirmed_at IS NOT NULL)
    RETURNING id, route_stop_id, state
)
UPDATE route_stops rs
SET active_handoff_id = ins.id
FROM ins
WHERE ins.route_stop_id = rs.id
  AND ins.state <> 'CONFIRMED';
