-- A journal of every ERP sync attempt, kept alongside the last-attempt columns on `orders`.
--
-- Until now an order carried only its most recent outcome (last_sync_op, last_sync_error,
-- last_synced_at). That answers "is it broken right now?" but erases the past: a delivery that
-- failed twice and then succeeded looks, forever after, as if it had always been fine. The operator
-- console could show the current failures and nothing else, so nobody could answer "how often does
-- this ERP reject us?" or "what happened to that BL last Tuesday?".
--
-- Provider-agnostic by construction. The row is written where every sync outcome already converges
-- (ErpSyncResultConsumer), which sees orders and returns alike and never learns which adapter
-- produced the result; the provider is stamped from the tenant's own erp.provider setting, so Odoo,
-- ERPNext and anything added later journal identically with no extra code.
--
-- Append-only, never updated. Retention is left to the operator for now; the table is small
-- (one row per sync attempt) and indexed on occurred_at so the console reads only the recent tail.

CREATE TABLE IF NOT EXISTS erp_sync_event (
    id            UUID PRIMARY KEY,
    occurred_at   TIMESTAMP    NOT NULL,
    provider      VARCHAR(40),
    op            VARCHAR(40),
    success       BOOLEAN      NOT NULL,
    error_reason  TEXT,
    order_id      UUID,
    rma_id        UUID,
    reference     VARCHAR(120)
);

-- The console reads the newest events first, optionally narrowed to the failures.
CREATE INDEX IF NOT EXISTS idx_erp_sync_event_occurred_at ON erp_sync_event (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_erp_sync_event_success ON erp_sync_event (success, occurred_at DESC);
-- Drill-down from a single delivery ("what has this order been through?").
CREATE INDEX IF NOT EXISTS idx_erp_sync_event_order ON erp_sync_event (order_id, occurred_at DESC);

COMMENT ON TABLE erp_sync_event IS
    'Append-only journal of ERP sync attempts (orders and returns, all providers). The `orders` '
    'columns hold the latest attempt; this table holds every attempt.';
COMMENT ON COLUMN erp_sync_event.reference IS
    'Human-readable handle for the console: the BL number, or the ERP reference when there is no BL.';
