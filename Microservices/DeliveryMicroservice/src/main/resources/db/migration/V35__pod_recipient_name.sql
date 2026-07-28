-- Records who signed for the parcel.
--
-- The ERP payload has always carried a "recipientName" (the Odoo chatter note renders it as
-- "Reçu par"), but nothing ever populated it: the field was absent from the POD request DTO and
-- from this table, so every proof of delivery reached Odoo anonymous. A photo shows the parcel;
-- this names the person who took it, which is what a delivery dispute actually turns on.
ALTER TABLE proof_of_delivery
    ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(150);
