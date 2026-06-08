-- =============================================================================
-- Persist the last ERP sync operation + error on the order so a terminal
-- SYNC_FAILED can be (a) explained in the System Health drill-down and
-- (b) replayed by the operator "Resync" action (which op to re-publish).
-- =============================================================================
ALTER TABLE orders ADD COLUMN IF NOT EXISTS last_sync_op    VARCHAR(40);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS last_sync_error TEXT;

-- Index the failure bucket so the health page's count/list stays cheap.
CREATE INDEX IF NOT EXISTS idx_orders_odoo_sync_status
    ON orders(odoo_sync_status);
