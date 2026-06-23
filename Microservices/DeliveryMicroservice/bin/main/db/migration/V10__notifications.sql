-- Persistent admin notifications. Today admin alerts are ephemeral (STOMP only, kept in the
-- browser's localStorage), so an offline admin misses ERP failures / SLA breaches and read-state
-- is per-browser. This table makes them durable, server-side, and shared across admins; live
-- delivery still happens over the existing STOMP topics.
CREATE TABLE notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(60)  NOT NULL,
    severity        VARCHAR(20)  NOT NULL DEFAULT 'info',
    title           VARCHAR(200),
    message         TEXT,
    order_ref       VARCHAR(100),
    delivery_id     VARCHAR(64),
    route_id        VARCHAR(64),
    driver_id       VARCHAR(64),
    driver_name     VARCHAR(150),
    client_name     VARCHAR(200),
    payload         JSONB,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    acknowledged    BOOLEAN NOT NULL DEFAULT FALSE,
    acknowledged_by VARCHAR(150),
    acknowledged_at TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_created_at ON notifications(created_at DESC);
CREATE INDEX idx_notifications_is_read    ON notifications(is_read);
CREATE INDEX idx_notifications_severity   ON notifications(severity);
