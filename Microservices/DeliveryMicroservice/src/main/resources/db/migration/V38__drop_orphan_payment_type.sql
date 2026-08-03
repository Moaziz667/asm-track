-- payment_type has no Java field, no reader and no writer: nothing in the service has referenced it
-- since V8 took the cash-on-delivery columns out. It survived that cleanup and has been carried on
-- every orders row since, in eight tenant schemas.
--
-- Dropped rather than repurposed. It was tempting to reuse it for the ERP payment term, but a column
-- whose name says "payment method" holding "30 days end of month" is a trap for whoever reads the
-- schema next — and the payment term itself is being removed from the mappable vocabulary, so there
-- is nothing to house.

ALTER TABLE orders DROP COLUMN IF EXISTS payment_type;
