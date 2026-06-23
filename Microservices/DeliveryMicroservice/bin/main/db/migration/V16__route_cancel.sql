-- Explicit route cancellation: a VALIDATED/IN_PROGRESS route can be aborted (kept for audit, not
-- deleted). Store the mandatory reason and the moment it was cancelled directly on the route so the
-- routes list / reports can show "Annulée · {reason}" without joining the audit log.
ALTER TABLE routes ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(500);
ALTER TABLE routes ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;
