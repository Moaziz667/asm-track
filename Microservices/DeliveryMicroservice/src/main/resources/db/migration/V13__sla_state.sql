-- Single source of truth for delivery SLA. Replaces the scattered, contradictory SLA engines
-- (SlaMonitoringService in-memory motifs, OpsAnalyticsService classification, RouteStop.sla_deadline,
-- frontend day-as-instant). One row per delivery: current phase + health + the one window-anchored
-- deadline. last_alerted_health gives restart-proof dedup so breaches never re-flood on redeploy.
CREATE TABLE sla_state (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id            UUID NOT NULL,
    phase                  VARCHAR(20)  NOT NULL,
    health                 VARCHAR(12)  NOT NULL,
    due_at                 TIMESTAMP,
    at_risk_at             TIMESTAMP,
    breached_at            TIMESTAMP,
    late_minutes           INTEGER,
    attributable_to_driver BOOLEAN NOT NULL DEFAULT FALSE,
    reason_key             VARCHAR(60),
    reason_params          JSONB,
    last_alerted_health    VARCHAR(12),
    last_transition_at     TIMESTAMP,
    suppress_alerts_until  TIMESTAMP,
    updated_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT sla_state_delivery_id_key UNIQUE (delivery_id),
    CONSTRAINT sla_state_delivery_fk FOREIGN KEY (delivery_id) REFERENCES deliveries(id) ON DELETE CASCADE
);

CREATE INDEX idx_sla_state_health ON sla_state(health);
CREATE INDEX idx_sla_state_phase  ON sla_state(phase);
CREATE INDEX idx_sla_state_due_at ON sla_state(due_at);
