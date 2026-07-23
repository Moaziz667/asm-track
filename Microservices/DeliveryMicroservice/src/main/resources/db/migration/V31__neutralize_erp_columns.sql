-- Neutralize ERP-provider naming: the odoo_* columns are ERP-agnostic in intent (they hold the
-- backorder reference and the ERP sync status for ANY provider — Odoo, ERPNext, Dux). Rename them to
-- erp_* so the domain model no longer names one specific ERP. Provider-specific behaviour stays in the
-- adapters (OdooErpAdapter, etc.), not in the schema.
--
-- Guarded so it is safe to (re)apply on a schema that was already renamed: PostgreSQL has no
-- "RENAME COLUMN IF EXISTS". Flyway runs this per tenant schema with the search_path set, so the
-- unqualified names resolve to the current tenant schema.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'orders'
                 AND column_name = 'odoo_sync_status') THEN
        ALTER TABLE orders RENAME COLUMN odoo_sync_status TO erp_sync_status;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'orders'
                 AND column_name = 'odoo_backorder_id') THEN
        ALTER TABLE orders RENAME COLUMN odoo_backorder_id TO erp_backorder_id;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'deliveries'
                 AND column_name = 'odoo_backorder_id') THEN
        ALTER TABLE deliveries RENAME COLUMN odoo_backorder_id TO erp_backorder_id;
    END IF;
END $$;

ALTER INDEX IF EXISTS idx_orders_odoo_sync_status RENAME TO idx_orders_erp_sync_status;
