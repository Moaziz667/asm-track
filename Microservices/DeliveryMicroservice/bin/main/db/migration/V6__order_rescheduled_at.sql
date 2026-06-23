-- ─────────────────────────────────────────────────────────────────────────────
-- Replan scheduled date (Phase 2, SLA)
-- On replan, the admin sets a NEW scheduled date so SLAs no longer measure against
-- the stale ERP (Odoo) date. Null until a delivery is replanned.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE orders ADD COLUMN rescheduled_at TIMESTAMP;
