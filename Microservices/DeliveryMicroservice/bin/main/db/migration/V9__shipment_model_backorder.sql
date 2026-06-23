-- ──────────────────────────────────────────────────────────────────────────
-- Shipment model: a sale order can have MANY deliveries (original + backorders),
-- mirroring Odoo (one sale.order -> many stock.picking). The picking identity
-- (BL number + Odoo backorder picking id) is a property of the shipment, so it
-- moves onto `deliveries`. This removes the need to clone an Order to represent a
-- backorder (the old workaround that relied on a 1:1 deliveries.order_id UNIQUE).
-- ──────────────────────────────────────────────────────────────────────────

ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS bl_number         VARCHAR(100);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS odoo_backorder_id INTEGER;

-- Backfill each existing delivery from its order's current picking identity
-- (the data is 1:1 today, so this is an exact copy).
UPDATE deliveries d
   SET bl_number        = o.bl_number,
       odoo_backorder_id = o.odoo_backorder_id
  FROM orders o
 WHERE d.order_id = o.id;

-- Allow many deliveries per order. A backorder is now a new delivery under the
-- same order, not a cloned order.
ALTER TABLE deliveries DROP CONSTRAINT IF EXISTS deliveries_order_id_key;
CREATE INDEX IF NOT EXISTS idx_deliveries_order_id ON deliveries(order_id);
