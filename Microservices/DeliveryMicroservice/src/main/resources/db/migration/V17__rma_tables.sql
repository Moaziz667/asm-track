-- V17 — RMA (Return Merchandise Authorization) tables.
--
-- The Rma / RmaItem JPA entities existed in code but no migration ever created their tables
-- (ddl-auto=none, schema.sql disabled), so the returns feature could never run. This migration
-- creates them for the first time.
--
-- Single-tenant note: there is intentionally NO company_id column — one instance serves one
-- company, so company identity (name/branding) is read from the `companies` singleton at render
-- time, never stored as a per-row partition key.
--
-- erp_sync_status closes the return loop with Odoo: a RESTOCKED return enqueues a reverse stock
-- move and the async result flips this PENDING_SYNC -> SYNCED / SYNC_FAILED (mirrors orders).

CREATE TABLE rma (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id     UUID NOT NULL,
    order_id        UUID,
    erp_order_id    VARCHAR(100),
    bl_number       VARCHAR(100),
    client_name     VARCHAR(255),
    status          VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED'
                        CHECK (status IN ('REQUESTED','APPROVED','RECEIVED','RESTOCKED','REJECTED','CANCELLED')),
    reason          TEXT,
    resolution_note TEXT,
    -- ERP reverse-move sync state (only meaningful once a return is RESTOCKED).
    erp_sync_status VARCHAR(40),
    erp_sync_error  TEXT,
    created_by      VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    received_at     TIMESTAMP,
    restocked_at    TIMESTAMP
);

CREATE INDEX idx_rma_status   ON rma(status);
CREATE INDEX idx_rma_delivery ON rma(delivery_id);
CREATE INDEX idx_rma_created  ON rma(created_at);

CREATE TABLE rma_item (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rma_id     UUID NOT NULL REFERENCES rma(id) ON DELETE CASCADE,
    sku        VARCHAR(100),
    name       VARCHAR(255),
    quantity   INTEGER NOT NULL,
    unit_price NUMERIC(12,3),
    condition  VARCHAR(20) DEFAULT 'RESELLABLE'
                   CHECK (condition IN ('RESELLABLE','DAMAGED')),
    reason     VARCHAR(255)
);

CREATE INDEX idx_rma_item_rma ON rma_item(rma_id);
