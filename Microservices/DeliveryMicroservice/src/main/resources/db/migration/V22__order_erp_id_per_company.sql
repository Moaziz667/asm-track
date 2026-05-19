-- Fix: ERP Order IDs should be unique per company, not globally.
-- This allows different tenants to have overlapping order IDs (e.g., 'S00001').

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_erp_order_id_key;

ALTER TABLE orders ADD CONSTRAINT orders_erp_order_id_company_id_key UNIQUE (erp_order_id, company_id);
