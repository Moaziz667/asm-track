-- ============================================================
-- Fix stuck outbox events after OutboxProcessor code fix
-- Run against: delivery_db
-- ============================================================

-- 1. Requeue ERP_SYNC_CANCELLATION (was failing due to LazyInitializationException — now fixed)
--    Reset to PENDING with 0 retries so the scheduler picks it up immediately.
UPDATE outbox_event
SET status      = 'PENDING',
    retry_count = 0,
    last_error  = NULL
WHERE id = 'f8395493-7715-497a-a4a1-9ae2d3516c30'
  AND event_type = 'ERP_SYNC_CANCELLATION';

-- 2. Discard ERP_SYNC_FAILURE (Odoo already rejected it — order S00073 is in a terminal
--    state on the ERP side; retrying will never succeed). Mark DEAD_LETTER to preserve audit.
UPDATE outbox_event
SET status     = 'DEAD_LETTER',
    last_error = 'Manually closed: ERP rejected sync for already-terminal Odoo order S00073'
WHERE id = 'cc587e1f-f671-46f9-9ead-116c7d5468cf'
  AND event_type = 'ERP_SYNC_FAILURE';

-- ============================================================
-- 3. ROOT CAUSE FIX: Reset all ERP_SYNC_STOCK events that were
--    silently skipped because odooSyncStatus defaulted to 'SYNCED'.
--    These events were marked PROCESSED/FAILED without ever calling Odoo.
-- ============================================================

-- 3a. Reset the Order rows so the OutboxProcessor won't skip them again.
--     Only touch orders whose delivery is DELIVERED or PARTIALLY_DELIVERED
--     but whose Odoo sync status is still 'SYNCED' (never actually synced).
UPDATE orders o
SET odoo_sync_status = 'PENDING_SYNC'
WHERE o.odoo_sync_status = 'SYNCED'
  AND EXISTS (
      SELECT 1 FROM deliveries d
      WHERE d.order_id = o.id
        AND d.status IN ('DELIVERED', 'PARTIALLY_DELIVERED')
  );

-- 3b. Re-queue the stuck ERP_SYNC_STOCK outbox events so they fire again.
UPDATE outbox_event
SET status      = 'PENDING',
    retry_count = 0,
    last_error  = 'Reset: was skipped due to SYNCED default status bug'
WHERE event_type = 'ERP_SYNC_STOCK'
  AND status IN ('FAILED', 'PROCESSED', 'PROCESSING');

