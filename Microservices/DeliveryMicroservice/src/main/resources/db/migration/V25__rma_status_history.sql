-- Immutable audit trail of every RMA status change (replaces the synthetic timeline in the admin drawer).
CREATE TABLE rma_status_history (
    id             UUID PRIMARY KEY,
    rma_id         UUID NOT NULL REFERENCES rma(id) ON DELETE CASCADE,
    from_status    VARCHAR(20),          -- null on creation
    to_status      VARCHAR(20) NOT NULL,
    note           TEXT,
    acted_by_name  VARCHAR(255),         -- nullable: public/system actions
    acted_by_role  VARCHAR(40),          -- ADMIN / DISPATCHER / CLIENT / SYSTEM …
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_rma_history_rma ON rma_status_history(rma_id, created_at);
