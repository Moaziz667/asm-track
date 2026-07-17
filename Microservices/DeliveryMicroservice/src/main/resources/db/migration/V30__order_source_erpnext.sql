-- Allow ERPNEXT as an order source alongside ODOO and DUX.
-- The domain enum OrderSource gained ERPNEXT when the ERPNext adapter landed; the DB check
-- constraint (set in V18) still only permitted ODOO/DUX, so importing an ERPNext Delivery Note
-- failed with orders_source_check. Widen the constraint to match the enum.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_source_check;
ALTER TABLE orders ADD CONSTRAINT orders_source_check CHECK (source IN ('ODOO', 'ERPNEXT', 'DUX'));
