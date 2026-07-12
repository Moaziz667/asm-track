-- Inbound return-shipment tracking: how the customer sends the goods back.
-- receivedAt already exists on rma (set on the RECEIVED transition).
ALTER TABLE rma ADD COLUMN tracking_number  VARCHAR(120);
ALTER TABLE rma ADD COLUMN shipping_carrier VARCHAR(120);
ALTER TABLE rma ADD COLUMN shipped_at       TIMESTAMP;
