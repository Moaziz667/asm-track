-- V14: Add handoff token and expiration columns to route_stops
-- Supports QR code digital handshakes for package custody transfer.

ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS handoff_token VARCHAR(100);
ALTER TABLE route_stops ADD COLUMN IF NOT EXISTS handoff_token_expires_at TIMESTAMP;
