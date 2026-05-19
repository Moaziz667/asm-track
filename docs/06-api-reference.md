# 06 — API Reference

> Only operationally important endpoints are listed. Trivial CRUD with no side effects is omitted.

## Legend

| Auth type | Meaning |
|-----------|---------|
| `ADMIN_COOKIE` | Admin JWT in HTTP-only `access_token` cookie (AppBackend / DeliveryMicroservice) |
| `DRIVER_BEARER` | Driver Bearer JWT in `Authorization` header |
| `INTERNAL` | `X-Internal-Secret` header — service-to-service only, not exposed externally |
| `PUBLIC` | No authentication required |

---

## AppBackend Endpoints

### POST /api/auth/admin/login
- **Auth:** PUBLIC
- **Body:** `{email, password}`
- **Response:** `{token, refreshToken, user: {id, name, email, role, companyId}}` + HTTP-only cookies
- **Side effects:** Issues `access_token` and `refresh_token` cookies
- **Tenant:** `companyId` embedded in JWT, null for SUPER_ADMIN

### POST /api/auth/admin/refresh
- **Auth:** PUBLIC (reads `refresh_token` cookie)
- **Response:** New tokens + refreshed cookies

### POST /api/auth/admin/logout
- **Auth:** PUBLIC
- **Side effects:** Clears `access_token` and `refresh_token` cookies (expires=past)

### POST /api/admin/users
- **Auth:** ADMIN_COOKIE (ADMIN or SUPER_ADMIN role)
- **Body:** `{name, email, password, role, companyId?}`
- **Side effects:** Fire-and-forget `POST /internal/audit` to DeliveryMicroservice (action=CREATE_ADMIN_USER)
- **Tenant:** SUPER_ADMIN can create for any company; ADMIN limited to own company

---

## DriverService Endpoints

### POST /api/auth/driver/login
- **Auth:** PUBLIC
- **Body:** `{phone, password}`
- **Response:** `{accessToken, refreshToken, driver: {id, name, phone}}`
- **Token:** 1h access, 7d refresh. No companyId in token (drivers are shared pool).

### POST /api/auth/driver/refresh-token
- **Auth:** PUBLIC
- **Body:** `{refreshToken}`

### PUT /api/driver/fcm-token
- **Auth:** DRIVER_BEARER
- **Body:** `{fcmToken}`
- **Side effects:** Stored in `Driver.fcmToken` — enables push notifications

### DELETE /api/driver/fcm-token
- **Auth:** DRIVER_BEARER
- **Side effects:** Clears FCM token — stops push notifications immediately

### GET /internal/drivers/available
- **Auth:** INTERNAL (X-Internal-Secret)
- **Response:** `List<InternalDriverResponse>` — active drivers for dispatch
- **Called by:** DeliveryMicroservice DispatchService

### PUT /internal/drivers/{id}/location
- **Auth:** INTERNAL
- **Body:** `{lat, lng}`
- **Called by:** DeliveryMicroservice directly (synchronous call in `updateLocation()`, no outbox)

### POST /internal/drivers/{id}/stats/increment
- **Auth:** INTERNAL
- **Body:** `{stat}` — "delivered" | "failed" | "cancelled"
- **Side effects:** `DriverStats.delivered/failed/cancelled++`, `DriverHistory` record created
- **Called by:** DeliveryMicroservice outbox (INCREMENT_DRIVER_STAT events)

---

## DeliveryMicroservice — Driver Endpoints

### POST /api/driver/deliveries/{id}/accept
- **Auth:** DRIVER_BEARER
- **Idempotency:** `acc-{id}`
- **State transition:** UNSCHEDULED → SCHEDULED
- **Side effects:** Atomic SQL update (SKIP LOCKED), WebSocket `delivery.scheduled`

### POST /api/driver/deliveries/{id}/pickup
- **Auth:** DRIVER_BEARER
- **Idempotency:** `pkp-{id}`
- **State transition:** SCHEDULED → PICKED_UP
- **Side effects:** WebSocket `delivery.picked_up`, outbox `INCREMENT_DRIVER_STAT`

### POST /api/driver/deliveries/{id}/transit
- **Auth:** DRIVER_BEARER
- **Body:** `{lat?, lng?}`
- **Idempotency:** `trns-{id}`
- **State transition:** PICKED_UP → IN_TRANSIT
- **Side effects:** OSRM route computed, SLA calculated, WebSocket `delivery.in_transit`

### POST /api/driver/deliveries/{id}/pod
- **Auth:** DRIVER_BEARER
- **Body:**
  ```json
  {
    "bonLivraisonPhotoBase64": "...",
    "packagePhotoBase64":      "...",
    "comment":                 "...",
    "lat":                     36.8190,
    "lng":                     10.1658,
    "isPartial":               false,
    "itemsDone": [
      {"sku": "SKU001", "quantityDone": 14}
    ]
  }
  ```
- **Idempotency:** `pod-{id}`
- **State transition:** IN_TRANSIT → DELIVERED (full) or PARTIALLY_DELIVERED (partial)
- **Side effects:**
  - Photos uploaded to MinIO (`pod-files` bucket)
  - `proof_of_delivery` row created with MinIO URLs
  - Outbox `ERP_SYNC_STOCK` enqueued
  - WebSocket `delivery.completed` or event for partial
  - FCM push to driver (confirmation)
  - PDF bon de livraison generated
- **Offline:** Hive queue on network error

### POST /api/driver/deliveries/{id}/fail
- **Auth:** DRIVER_BEARER
- **Body:** `{failureCode, failureComment?}`
- **Failure codes:** CLIENT_ABSENT, REFUSED, WRONG_ADDRESS, DAMAGED, OTHER
- **Idempotency:** `fail-{id}-{failureCode}`
- **State transition:** PICKED_UP or IN_TRANSIT → FAILED
- **Side effects:** Outbox `ERP_SYNC_FAILURE`, WebSocket `delivery.failed`

### POST /api/driver/deliveries/{id}/cancel
- **Auth:** DRIVER_BEARER
- **Body:** `{reason?}`
- **Idempotency:** `can-{id}`
- **State transition:** SCHEDULED/PICKED_UP → CANCELLED
- **Side effects:** Outbox `ERP_SYNC_CANCELLATION`, WebSocket `delivery.cancelled`

### POST /api/driver/deliveries/{id}/report
- **Auth:** DRIVER_BEARER
- **Body:** `{reportType, description?}`
- **Side effects:** `DeliveryReport` record created

### POST /api/driver/deliveries/location
- **Auth:** DRIVER_BEARER
- **Body:** `{lat, lng}`
- **Side effects:** `tracking` table row inserted; WebSocket `driver.location_updated` pushed to admin on `/topic/admin/{companyId}/routes`; DriverService location updated synchronously via `PUT /internal/drivers/{id}/location`

### POST /api/driver/routes/{routeId}/start
- **Auth:** DRIVER_BEARER
- **Idempotency:** `start-route-{routeId}`
- **Side effects:** Route status → IN_PROGRESS, WebSocket ROUTE_STARTED to driver topic

### POST /api/driver/routes/{routeId}/stops/{stopId}/arrive
- **Auth:** DRIVER_BEARER
- **Idempotency:** `arrive-{routeId}-{stopId}`
- **Side effects:** `route_stop.arrived_at` set, `sla_status` computed (ON_TIME/LATE/EARLY)

---

## DeliveryMicroservice — Admin Endpoints

### GET /api/admin/deliveries
- **Auth:** ADMIN_COOKIE (ADMIN | DISPATCHER | SUPER_ADMIN)
- **Query params:** `status`, `driverId`, `date`, `source`, `zoneId`, `unpinned`, pagination
- **Tenant:** Filtered by `companyId` from JWT automatically

### POST /api/admin/deliveries/{id}/assign
- **Auth:** ADMIN_COOKIE
- **Body:** `{driverId}`
- **Idempotency:** Idempotency-Key header
- **State transition:** UNSCHEDULED → SCHEDULED
- **Side effects:** FCM push to driver, WebSocket `delivery.scheduled`

### POST /api/admin/deliveries/{id}/create-backorder
- **Auth:** ADMIN_COOKIE
- **Precondition:** Delivery must be PARTIALLY_DELIVERED
- **Side effects:**
  - Reads `order.odooBackorderId` (Odoo picking ID from partial sync)
  - Creates new Order with synthetic `erpOrderId = S00042-B{timestamp}`
  - Sets `odooBackorderId` on new order (picking ID)
  - Creates new Delivery in UNSCHEDULED status
- **Operational risk:** If `odooBackorderId` is null, the new delivery cannot be ERP-synced on completion

### POST /api/admin/deliveries/orders/{orderId}/cancel
- **Auth:** ADMIN_COOKIE
- **Idempotency:** Idempotency-Key header
- **Body:** `{reason?}`
- **Side effects:** Outbox `ERP_SYNC_CANCELLATION`, WebSocket `delivery.cancelled`
- **Odoo flow:** ErpAdapterService calls `action_unlock` then `action_cancel` on sale.order

### POST /api/admin/routes
- **Auth:** ADMIN_COOKIE
- **Idempotency:** Idempotency-Key header
- **Body:** `{name, date, driverId, vehicleId, city, plannedStartTime, plannedEndTime}`
- **State:** Created in DRAFT

### PUT /api/admin/routes/{id}/validate
- **Auth:** ADMIN_COOKIE
- **Idempotency:** Idempotency-Key header
- **Side effects:** Route DRAFT → VALIDATED, WebSocket `route.validated`, FCM push to driver

### POST /api/admin/routes/{id}/optimize
- **Auth:** ADMIN_COOKIE
- **Side effects:** Calls OSRM for distance matrix, returns suggested stop reorder

### PUT /api/admin/routes/{id}/apply-optimization
- **Auth:** ADMIN_COOKIE
- **Idempotency:** Idempotency-Key header
- **Side effects:** Stop order updated, ETAs recalculated, WebSocket `route.schedule_changed`

### POST /api/admin/routes/transfer-stops
- **Auth:** ADMIN_COOKIE
- **Idempotency:** Idempotency-Key header
- **Body:** `{fromRouteId, toRouteId, stopIds[]}`
- **Side effects:** Stops moved, ETAs recalculated on both routes, WebSocket STOPS_TRANSFERRED_OUT/IN

### GET /api/admin/routes/{id}/report
- **Auth:** ADMIN_COOKIE
- **Tenant:** Scoped by `company_id` via Hibernate filter
- **Returns:** Immutable closure report JSON (`RouteReportResponse`) — header, KPIs, status breakdown, timeline, stops, movements, geometry, POD gallery, audit trail
- **Behavior:** Reads snapshot from `route_report` table (created at close time by `RouteExecutionService`). If absent (legacy route closed before V30), computes and persists on the fly.

### GET /api/admin/routes/{id}/report/pdf
- **Auth:** ADMIN_COOKIE
- **Returns:** PDF (`application/pdf`) — `rapport-tournee-{id}.pdf`
- **Sections:** info boxes, 3×3 KPI grid, status breakdown table, stops table with delays, mouvements et exceptions
- **Implementation:** `RouteReportPdfService` extends `BasePdfService` (OpenPDF / com.lowagie)

### GET /api/admin/erp/pending-orders
- **Auth:** ADMIN_COOKIE
- **Query:** `limit` (max 300)
- **Side effects:** Passes through to ErpAdapterService GET /api/erp/lookup/pending-orders

### POST /api/admin/erp/import-order/{erpOrderId}
- **Auth:** ADMIN_COOKIE
- **Idempotency:** erpOrderId itself (prevents double import)
- **Side effects:**
  - Order + Delivery created (source=ODOO, status=UNSCHEDULED)
  - Outbox `ERP_SYNC_STOCK` enqueued (initial sync)
  - WebSocket `erp.orders_ready`

### GET /api/public/track/{deliveryId}
- **Auth:** PUBLIC
- **Response:** Delivery status, GPS, ETA (sensitive fields hidden)

### GET /api/admin/ops/overview
- **Auth:** ADMIN_COOKIE (ADMIN | DISPATCHER | MANAGER | SUPER_ADMIN)
- **Query:** `period=day`
- **Response:** SLA stats, dispatch lanes, exceptions summary

---

## ErpAdapterService Endpoints

All require `X-Internal-Secret` header. Not exposed through API Gateway to external clients.

### POST /api/erp/sync/full-delivery
- **Body:** `{erpOrderId, backorderPickingId?, transactionId}`
- **Response:** `{success: boolean}`
- **Idempotency:** `transactionId` → H2 idempotent_transaction table
- **In-flight guard:** Same `erpOrderId` cannot be synced concurrently

### POST /api/erp/sync/partial-delivery
- **Body:** `{erpOrderId, items: [{sku, quantityDone}], transactionId}`
- **Response:** `{success, pickingId, backorderPickingId}`
- **backorderPickingId:** Integer Odoo picking ID for the newly created backorder — stored on Order.odooBackorderId

### POST /api/erp/sync/order-cancellation
- **Params:** `erpOrderId`, `transactionId`
- **Odoo calls:** `action_unlock` → `action_cancel` → verify `state=cancel`

### POST /api/erp/sync/failure
- **Body:** `{erpOrderId, failureCode, comment, transactionId}`
- **Side effects:** Adds note/chatter message to Odoo sale.order

### GET /api/erp/lookup/pending-orders
- **Params:** `limit` (1-300), `X-Company-Id`
- **Response:** Orders in Odoo that have not yet been imported into ASM Track
