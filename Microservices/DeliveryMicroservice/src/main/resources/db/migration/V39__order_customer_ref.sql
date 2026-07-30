-- The customer's own reference for the order — their purchase-order number, e.g. PO-2026-4471.
--
-- Mappable since the field-mapping screen shipped, resolved in the preview, and dropped at import:
-- an integrator could map it, see it on the import list, and find nothing on the delivery.
--
-- It could not borrow erp_external_ref. Despite the similar name that column holds the ERP's own
-- sale-order reference (S00110), which sync-back, resync, invoicing and backorder grouping all
-- resolve against; writing a customer's reference there would break four flows at once. The
-- canonical field was renamed EXTERNAL_REF -> CUSTOMER_REF for the same reason: two near-homonyms
-- for "external to ASM" and "external to the ERP too" is a trap.
--
-- Display and search only. ASM never sends it back.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_ref VARCHAR(120);

COMMENT ON COLUMN orders.customer_ref IS
    'The end customer''s own order reference, as mapped from the ERP. Distinct from erp_external_ref, '
    'which holds the ERP sale-order reference the sync flows depend on.';
