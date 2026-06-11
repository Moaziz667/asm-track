-- V18 — Order source is ERP-only (ODOO or DUX). The legacy client-app source (APP) was removed:
-- every order is imported from an ERP. No data migration needed — existing rows are all 'ODOO'.
-- This widens the allowed set to add 'DUX' (second ERP) and drops 'APP'.

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_source_check;

-- Safety: should be a no-op (no APP rows exist), but guard against any stray legacy row.
UPDATE orders SET source = 'ODOO' WHERE source = 'APP';

ALTER TABLE orders ADD CONSTRAINT orders_source_check CHECK (source IN ('ODOO', 'DUX'));
