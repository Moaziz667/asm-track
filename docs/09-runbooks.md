# 09 — Operational Runbooks

## Runbook 1: Outbox Dead Letter — Event Permanently Failed

**Trigger:** `outbox_event.status = 'FAILED'` (retry_count > 50). Slack alert received via webhook.

### Diagnosis

```sql
-- Find all failed outbox events
SELECT id, event_type, retry_count, last_error, created_at, processed_at
FROM outbox_event
WHERE status = 'FAILED'
ORDER BY created_at DESC;

-- Inspect the event payload
SELECT id, event_type, payload, last_error
FROM outbox_event
WHERE id = '{eventId}';
```

### Common Causes and Fixes

**Cause 1: Odoo unreachable during the 16-minute retry window**
```sql
-- Fix: Reset event for retry after Odoo is restored
UPDATE outbox_event
SET status = 'PENDING', retry_count = 0, last_error = NULL
WHERE id = '{eventId}';
```

**Cause 2: Order not found in Odoo (erpOrderId wrong or deleted)**
```sql
-- Check payload
SELECT payload FROM outbox_event WHERE id = '{eventId}';
-- Look at the order
SELECT id, erp_order_id, odoo_sync_status FROM orders WHERE id = '{deliveryId from payload}';
-- If erpOrderId is invalid, fix the order then reset
UPDATE outbox_event SET status = 'PENDING', retry_count = 0 WHERE id = '{eventId}';
```

**Cause 3: Data issue in payload (malformed JSON or missing field)**
```sql
-- Update payload with corrected data, then reset
UPDATE outbox_event
SET payload = '{"deliveryId":"...", "isPartial": false}',
    status = 'PENDING',
    retry_count = 0,
    last_error = NULL
WHERE id = '{eventId}';
```

**Cause 4: Concurrent processing (PROCESSING status stuck)**
```sql
-- Events stuck in PROCESSING (service crashed mid-processing)
SELECT * FROM outbox_event WHERE status = 'PROCESSING' AND created_at < NOW() - INTERVAL '5 minutes';
-- Reset stuck events
UPDATE outbox_event
SET status = 'PENDING'
WHERE status = 'PROCESSING' AND created_at < NOW() - INTERVAL '5 minutes';
```

---

## Runbook 2: ERP Sync Not Completing

**Symptom:** Delivery is DELIVERED in ASM Track but stock not updated in Odoo. `outbox_event.status = PENDING` with high retry_count.

### Check Outbox State

```sql
SELECT id, event_type, retry_count, last_error, created_at
FROM outbox_event
WHERE status IN ('PENDING', 'PROCESSING')
  AND retry_count > 3
ORDER BY created_at ASC;
```

### Check ErpAdapterService Logs

```bash
docker logs erp-adapter --tail 100 | grep "erpOrderId"
```

Look for:
- `operation=syncFullDelivery` → check if `success=false` and why
- `inFlight` → order stuck in-flight guard? Service restarted mid-sync?
- JSON-RPC errors from Odoo

### Check Odoo Picking State Directly

From `last_error` get the Odoo picking ID, then check in Odoo:
- Inventory → Transfers → search by reference
- If picking state is `done`: the sync actually succeeded. Mark the outbox event processed:
  ```sql
  UPDATE outbox_event SET status = 'PROCESSED', processed_at = NOW() WHERE id = '{eventId}';
  UPDATE orders SET odoo_sync_status = 'SYNCED' WHERE id = '{orderId}';
  ```
- If picking state is `assigned` or `confirmed`: Odoo sync genuinely failed. Fix the root cause, then reset the outbox event.

### Check Idempotency Cache

If the same transactionId was used but marked FAILED in H2, the retry will re-execute (FAILED results are not cached). This is correct behavior — no action needed.

---

## Runbook 3: Driver Offline — Deliveries Not Syncing

**Symptom:** Driver reports POD submitted but admin dashboard still shows IN_TRANSIT. Driver had poor connectivity.

### Driver App Side

Ask driver to:
1. Open app → check if offline queue indicator is visible
2. Check network connectivity
3. Pull-to-refresh to trigger queue flush

### Server Side

If driver has connectivity but events aren't coming through:

```sql
-- Check if POD was ever received (delivery status)
SELECT id, status, completed_at, failed_at FROM deliveries WHERE id = '{deliveryId}';

-- Check if pod exists
SELECT * FROM proof_of_delivery WHERE delivery_id = '{deliveryId}';

-- Check idempotency (was pod-{deliveryId} ever processed?)
SELECT * FROM processed_requests WHERE request_key LIKE 'pod-{deliveryId}%';
```

If nothing received server-side:
- Driver should force-submit from the offline queue (pull down to refresh)
- If still failing, driver can try from a different network
- Last resort: admin can manually mark delivery completed via admin dashboard

---

## Runbook 4: Backorder Not Created in Odoo

**Symptom:** Admin created a backorder in ASM Track, but no corresponding backorder picking visible in Odoo.

### Check Order State

```sql
SELECT id, erp_order_id, odoo_backorder_id, odoo_sync_status
FROM orders
WHERE erp_order_id LIKE '%{originalOrderRef}%'
ORDER BY created_at DESC;
```

Expected: Two rows — original (e.g., `S00042`) and backorder (e.g., `S00042-B{timestamp}`).

Check `odoo_backorder_id` on the **original** order (the partial delivery):
- Should be the Odoo picking ID (integer) returned by `syncPartialDelivery`
- If null: the partial sync event may have failed before Odoo created the backorder

### Check Outbox for Original Delivery

```sql
-- Find the ERP_SYNC_STOCK event for the original partial delivery
SELECT id, event_type, status, retry_count, last_error, payload
FROM outbox_event
WHERE payload LIKE '%{originalDeliveryId}%'
  AND event_type = 'ERP_SYNC_STOCK';
```

If status = FAILED:
1. Fix root cause
2. Reset: `UPDATE outbox_event SET status='PENDING', retry_count=0 WHERE id='{eventId}'`
3. Wait for OutboxProcessor to re-process (20s)
4. After success: `orders.odoo_backorder_id` will be populated
5. Admin can then re-create the backorder in the app (or backorder picking already created in Odoo)

---

## Runbook 5: Order Cancellation Failing in Odoo

**Symptom:** Admin cancelled an order in ASM Track, but Odoo still shows sale order in `sale` state.

### Check Outbox

```sql
SELECT id, status, retry_count, last_error, payload
FROM outbox_event
WHERE event_type = 'ERP_SYNC_CANCELLATION'
  AND payload LIKE '%{orderId}%';
```

### Common Error: Order Locked

Odoo 19 auto-locks confirmed orders. The ErpAdapterService handles this by calling `action_unlock` before `action_cancel`. But if the order is in a special state (e.g., partially invoiced), cancellation may be blocked.

Check `last_error` — if it contains `"cannot cancel"` or `"UserError"`:
1. Manually unlock and cancel in Odoo UI
2. Then mark the outbox event as processed:
   ```sql
   UPDATE outbox_event SET status='PROCESSED', processed_at=NOW() WHERE id='{eventId}';
   ```

---

## Runbook 6: SLA Alerts Flooding

**Symptom:** Admin dashboard shows constant SLA breach alerts but deliveries seem on time.

### Check SLA Config

SLA thresholds are very low in dev config:
```env
OPS_SLA_WAITING_MINUTES=1   # 1 minute! Should be 15 for production
OPS_SLA_TRANSIT_MINUTES=2   # 2 minutes! Should be 30 for production
```

Fix:
```env
# docker-compose.yml → delivery-service environment
OPS_SLA_WAITING_MINUTES=15
OPS_SLA_ASSIGN_MINUTES=20
```

Then restart delivery-service.

### Check De-duplication

SLA de-duplication is in-memory (`ConcurrentHashMap`). A service restart resets the de-duplication map, potentially causing a burst of duplicate alerts.

---

## Runbook 7: WebSocket Disconnection — Admin Not Receiving Updates

**Symptom:** Admin dashboard not showing real-time delivery updates. Status stuck.

### Diagnose

1. Open browser DevTools → Network → WS tab
2. Check if `/ws` WebSocket connection is established
3. Look for SockJS handshake: `/ws/info` and `/ws/...` upgrade request

### Common Causes

**SockJS reconnect:** SockJS retries automatically. Check if connection was recently lost and reconnected.

**Stale auth token:** WebSocket CONNECT uses Bearer token. If token expired during session, reconnect with fresh token fails silently.
- Fix: Force page refresh (triggers new login if token expired)

**Topic isolation:** Admin subscribed to `/topic/admin/deliveries` but company-scoped events go to `/topic/admin/{companyId}/deliveries`.
- This is handled in the admin app automatically via `admin_company_id` from localStorage.
- If `admin_company_id` is stale, logout and log back in.

**Server side:** Check delivery-service logs for WebSocket-related errors:
```bash
docker logs delivery-service --tail 200 | grep -i websocket
```

---

## Runbook 8: Monitoring Queries

### Overall System Health

```sql
-- Outbox backlog
SELECT status, count(*), max(retry_count), max(created_at)
FROM outbox_event
WHERE status != 'PROCESSED'
GROUP BY status;

-- Deliveries by status (last 24h)
SELECT status, count(*)
FROM deliveries
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY status;

-- Active routes
SELECT status, count(*) FROM routes GROUP BY status;

-- Dead letters
SELECT id, event_type, retry_count, last_error, created_at
FROM outbox_event
WHERE status = 'FAILED'
ORDER BY created_at DESC;
```

### Idempotency Health

```sql
-- Check processed_requests table size (should be cleaned up periodically)
SELECT count(*), min(created_at), max(created_at)
FROM processed_requests;
```

### ERP Sync Lag

```sql
-- Orders with PENDING_SYNC status (waiting to be synced)
SELECT id, erp_order_id, odoo_sync_status, created_at
FROM orders
WHERE odoo_sync_status = 'PENDING_SYNC'
ORDER BY created_at ASC;
```
