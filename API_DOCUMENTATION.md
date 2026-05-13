# ASM Track — API Documentation

> **Version:** 1.0  
> **Last updated:** 2026-05-13  
> **Base URL (via API Gateway):** `http://localhost:80`

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Admin — User & Client Management](#2-admin--user--client-management)
3. [Admin — Deliveries](#3-admin--deliveries)
4. [Admin — Routes](#4-admin--routes)
5. [Admin — Operations & Dispatch](#5-admin--operations--dispatch)
6. [Admin — Reports & Analytics](#6-admin--reports--analytics)
7. [Admin — Fleet (Drivers & Vehicles)](#7-admin--fleet-drivers--vehicles)
8. [Admin — ERP Import](#8-admin--erp-import)
9. [Admin — Zones & Company](#9-admin--zones--company)
10. [Driver — Auth & Profile](#10-driver--auth--profile)
11. [Driver — Deliveries](#11-driver--deliveries)
12. [Driver — Routes](#12-driver--routes)
13. [Public](#13-public)
14. [Internal (Service-to-Service)](#14-internal-service-to-service)
15. [ERP Adapter](#15-erp-adapter-service)

---

## How Authentication Works

All protected endpoints require a **Bearer JWT token** in the `Authorization` header:

```
Authorization: Bearer <token>
```

Tokens are obtained via login endpoints. Admin and driver tokens are separate — they cannot be used interchangeably.

Admin tokens also encode `companyId` so the backend automatically scopes all data to the right company. You never pass `companyId` manually.

Internal service-to-service calls use a shared secret header instead of JWT:
```
X-Internal-Secret: asm-internal-2026
```

---

## 1. Authentication

### Admin Auth — `/api/auth/admin`

**POST `/api/auth/admin/login`**  
Log in as an admin user. Sets `access_token` and `refresh_token` as httpOnly cookies and also returns them in the response body.

```json
// Request
{ "email": "admin@company.com", "password": "secret" }

// Response 200
{ "accessToken": "...", "refreshToken": "...", "user": { "id": "...", "name": "...", "role": "ADMIN" } }
```

---

**POST `/api/auth/admin/refresh`**  
Rotate tokens using the refresh cookie. Call this when the access token expires.

```
// No body needed — reads refresh_token from cookie
// Response 200
{ "accessToken": "...", "refreshToken": "..." }
```

---

**POST `/api/auth/admin/logout`**  
Clear auth cookies. Call on logout.

```
// No body needed
// Response 200
```

---

### Driver Auth — `/api/auth/driver` (DriverService)

**POST `/api/auth/driver/register`**  
Register a new driver account.

```json
// Request
{ "name": "Ahmed Ben Salem", "phone": "21612345678", "password": "pass123" }
// Response 201
{ "id": "...", "name": "...", "phone": "..." }
```

---

**POST `/api/auth/driver/login`**  
Driver login. Returns JWT access + refresh tokens.

```json
// Request
{ "phone": "21612345678", "password": "pass123" }
// Response 200
{ "accessToken": "...", "refreshToken": "..." }
```

---

**POST `/api/auth/driver/refresh-token`**  
Refresh driver access token.

```json
// Request
{ "refreshToken": "..." }
// Response 200
{ "accessToken": "...", "refreshToken": "..." }
```

---

**PUT `/api/auth/driver/change-password`** 🔒 Driver  
Change driver password.

```json
{ "oldPassword": "...", "newPassword": "...", "confirmPassword": "..." }
```

---

## 2. Admin — User & Client Management

### Admin Users — `/api/admin/users`

**POST `/api/admin/users`** 🔒 Admin  
Create a new admin user for the company.

```json
// Request
{ "name": "Khalil Mansouri", "email": "k.mansouri@company.com", "role": "ADMIN", "phone": "21699000000" }
// Response 201
{ "id": "...", "name": "...", "email": "...", "role": "ADMIN", "active": true }
```

---

**GET `/api/admin/users`** 🔒 Admin  
List all admin users for the authenticated company.

```json
// Response 200
[{ "id": "...", "name": "...", "email": "...", "role": "ADMIN", "active": true }]
```

---

**PATCH `/api/admin/users/{id}/status`** 🔒 Admin  
Enable or disable an admin user account.

```json
// Request
{ "active": false }
```

---

### Admin Clients — `/api/admin/clients`

**GET `/api/admin/clients`** 🔒 Admin  
List clients with optional search and pagination.

```
GET /api/admin/clients?search=ahmed&page=0&size=20
```

---

### Profile — `/api/profile`

**GET `/api/profile`** 🔒  
Get the authenticated user's profile.

**PUT `/api/profile`** 🔒  
Update profile info.

```json
{ "firstName": "Aziz", "lastName": "Hadjkacem", "phone": "21600000000" }
```

**PUT `/api/profile/password`** 🔒  
Change password.

```json
{ "oldPassword": "...", "newPassword": "..." }
```

---

## 3. Admin — Deliveries

**Base path:** `/api/admin/deliveries`  
All endpoints require admin JWT.

---

**GET `/api/admin/deliveries`**  
Search deliveries with filters and pagination.

```
GET /api/admin/deliveries?status=UNSCHEDULED&driverId=...&date=2026-05-13&page=0&size=25
```

| Param | Type | Description |
|---|---|---|
| `status` | string | Filter by status (UNSCHEDULED, SCHEDULED, IN_TRANSIT, DELIVERED, FAILED...) |
| `driverId` | UUID | Filter by assigned driver |
| `date` | date | Filter by creation date |
| `source` | string | Filter by source (ODOO, APP) |
| `zoneId` | UUID | Filter by delivery zone |
| `page` / `size` | int | Pagination |

---

**GET `/api/admin/deliveries/{id}`**  
Get full delivery detail including order info and status history.

---

**GET `/api/admin/deliveries/{id}/history`**  
Get the complete status change history for a delivery.

---

**POST `/api/admin/deliveries/{id}/assign`**  
Assign a delivery to a driver.

```json
{ "driverId": "...", "assignedAt": "2026-05-13T09:00:00" }
```

---

**POST `/api/admin/deliveries/{id}/cancel`**  
Cancel a delivery before it enters transit.

```json
{ "reason": "Customer request" }
```

---

**POST `/api/admin/deliveries/{id}/pin-dropoff`**  
Manually set GPS coordinates for the dropoff address.

```json
{ "lat": 36.8065, "lng": 10.1815, "address": "12 Rue de la Paix", "city": "Tunis" }
```

---

**GET `/api/admin/deliveries/{id}/geocode`**  
Auto-geocode the delivery's dropoff address using Nominatim. Returns `lat`, `lng`, `formattedAddress`.

---

**GET `/api/admin/deliveries/reverse-geocode`**  
Convert GPS coordinates to a human-readable address.

```
GET /api/admin/deliveries/reverse-geocode?lat=36.8065&lng=10.1815
```

---

**POST `/api/admin/deliveries/{id}/confirm-return`**  
Confirm that a returned parcel has been received at the depot.

```json
{ "note": "Received in good condition" }
```

---

**POST `/api/admin/deliveries/{id}/create-backorder`**  
Create a new delivery task for items not delivered (partial delivery backorder).

---

**GET `/api/admin/deliveries/{id}/pod`**  
Get the proof of delivery (POD) — signature, recipient name, photos.

---

**GET `/api/admin/deliveries/{id}/bon-livraison`**  
Download the bon de livraison as a PDF.

> The PDF includes a COD box **only** if the order's payment term is "Immediate Payment".

---

**POST `/api/admin/deliveries/orders/{orderId}/cancel`**  
Cancel an order and sync the cancellation back to Odoo.

---

**GET `/api/admin/deliveries/stats`**  
Aggregated delivery stats per driver for a given period.

```
GET /api/admin/deliveries/stats?period=day&from=2026-05-01&to=2026-05-13
```

---

**POST `/api/admin/deliveries/sync-zones`**  
Recalculate zone assignments for all existing deliveries.

---

## 4. Admin — Routes

**Base path:** `/api/admin/routes`  
All endpoints require admin JWT.

---

**GET `/api/admin/routes`**  
List routes with filters.

```
GET /api/admin/routes?status=VALIDATED&driverId=...&date=2026-05-13&city=Tunis
```

---

**POST `/api/admin/routes`**  
Create a new route.

```json
{
  "driverId": "...",
  "vehicleId": "...",
  "date": "2026-05-13",
  "plannedStartTime": "08:00",
  "city": "Tunis",
  "stops": [
    { "deliveryId": "...", "stopOrder": 1 }
  ]
}
```

---

**GET `/api/admin/routes/{id}/full`**  
Get route with all stops fully populated (delivery + order + driver info).

---

**PUT `/api/admin/routes/{id}/validate`**  
Validate a route — triggers driver notification and locks the stop list.

---

**POST `/api/admin/routes/{id}/optimize`**  
Get an optimized stop order suggestion (uses OSRM routing). Does **not** apply changes automatically.

---

**PUT `/api/admin/routes/{id}/apply-optimization`**  
Apply the suggested optimized order and recalculate ETAs.

---

**PUT `/api/admin/routes/{id}/reorder`**  
Manually reorder stops.

```json
{ "stopIds": ["uuid1", "uuid2", "uuid3"] }
```

---

**GET `/api/admin/routes/{id}/eta-details`**  
Get ETA and SLA status for every stop on the route.

---

**POST `/api/admin/routes/{id}/recalculate`**  
Recalculate all ETAs from the planned departure time.

---

**POST `/api/admin/routes/{id}/stops/active`**  
Add a new stop to a route that is already VALIDATED or IN_PROGRESS. Notifies the driver.

```json
{ "deliveryId": "...", "timeWindowStart": "10:00", "timeWindowEnd": "12:00" }
```

---

**DELETE `/api/admin/routes/{id}/stops/{stopId}`**  
Remove a stop from a route.

---

**POST `/api/admin/routes/transfer-stops`**  
Move stops from one route/driver to another.

```json
{
  "fromRouteId": "...",
  "toRouteId": "...",
  "stopIds": ["...", "..."]
}
```

---

**GET `/api/admin/routes/{id}/pdf`**  
Download the feuille de route as a PDF.

> The PDF shows a "TOTAL COD À ENCAISSER" section and COD column **only** for stops where `isCod = true`.

---

**GET `/api/admin/routes/{id}/driver-location`**  
Get the latest GPS position of the driver assigned to this route.

---

## 5. Admin — Operations & Dispatch

**Base path:** `/api/admin/ops`

---

**GET `/api/admin/ops/overview`**  
Full ops overview: SLA compliance, dispatch lane counts, exception feed.

```
GET /api/admin/ops/overview?period=day&waitingSlaMinutes=30&transitSlaMinutes=60
```

---

**GET `/api/admin/ops/lanes`**  
Dispatch lanes with counters and top deliveries per lane (WAITING, IN_TRANSIT, DELIVERED, FAILED).

---

**GET `/api/admin/ops/alerts`**  
Prioritized alert feed for the dispatch desk — SLA breaches, exceptions, failures.

```
GET /api/admin/ops/alerts?limit=20
```

---

**GET `/api/admin/ops/exceptions`**  
Exception feed: failed, partial, returned deliveries.

```
GET /api/admin/ops/exceptions?motif=FAILED&driverId=...&zone=Tunis
```

---

**POST `/api/admin/ops/exceptions/{deliveryId}/reassign`**  
Quick action: reassign an exception delivery to a different driver.

```json
{ "newDriverId": "..." }
```

---

**POST `/api/admin/ops/exceptions/{deliveryId}/replan`**  
Quick action: move a failed delivery back to the waiting queue.

```json
{ "zone": "Tunis" }
```

---

## 6. Admin — Reports & Analytics

**Base path:** `/api/admin/reports`

---

**GET `/api/admin/reports/dashboard`**  
Main dashboard KPIs: delivery volumes, SLA compliance rate, COD collected, average delivery time.

```
GET /api/admin/reports/dashboard?period=week&from=2026-05-06&to=2026-05-13
```

---

**GET `/api/admin/reports/kpi`**  
Detailed KPI breakdown per driver and zone.

---

**GET `/api/admin/reports/analytics/pdf`**  
Download the global activity report as a PDF.

---

**GET `/api/admin/reports/drivers/{driverId}/performance/pdf`**  
Download individual driver performance report as a PDF.

---

**GET `/api/admin/reports/settings`**  
Get current SLA thresholds (waiting SLA minutes, transit SLA minutes).

---

**POST `/api/admin/reports/settings`**  
Update an SLA threshold at runtime — no restart needed.

```json
{ "key": "OPS_SLA_TRANSIT_MINUTES", "value": "45" }
```

---

## 7. Admin — Fleet (Drivers & Vehicles)

### Drivers — `/api/admin/fleet/drivers`

**GET `/api/admin/fleet/drivers`**  
List all active drivers for the company with availability status.

**GET `/api/admin/fleet/drivers/available`**  
List drivers available for a given date and time window.

```
GET /api/admin/fleet/drivers/available?date=2026-05-13&startTime=08:00&endTime=18:00
```

**GET `/api/admin/fleet/drivers/stats`**  
Driver performance stats: completed deliveries, on-time rate, COD collected.

---

### Vehicles — `/api/admin/vehicles`

**GET `/api/admin/vehicles`**  
List all vehicles.

**POST `/api/admin/vehicles`**  
Create a vehicle.

```json
{ "name": "Fourgon 1", "plate": "123TU4567", "type": "VAN", "capacityKg": 500 }
```

**PUT `/api/admin/vehicles/{id}`**  
Update vehicle info.

**PATCH `/api/admin/vehicles/{id}/status`**  
Set vehicle operational status (ACTIVE / MAINTENANCE / INACTIVE).

**PUT `/api/admin/vehicles/{id}/assign`**  
Assign vehicle to a driver.

```json
{ "driverId": "..." }
```

**DELETE `/api/admin/vehicles/{id}`**  
Delete a vehicle.

---

## 8. Admin — ERP Import

**Base path:** `/api/admin/erp`  
All endpoints require admin JWT.

---

**GET `/api/admin/erp/pending-orders`**  
List orders confirmed in Odoo that have not yet been imported into ASM Track.

```
GET /api/admin/erp/pending-orders?limit=100
```

Returns: order reference, client name, phone, address, total amount, scheduled date.

---

**GET `/api/admin/erp/pending-orders/{erpOrderId}`**  
Preview a specific pending order — full detail including all line items, weight, COD status.

---

**POST `/api/admin/erp/import-order/{erpOrderId}`**  
Import a single pending order. Creates an `Order` + `Delivery` (UNSCHEDULED) in ASM Track.

> Returns 409 Conflict if the order was already imported.

---

**POST `/api/admin/erp/bulk-import`**  
Import multiple pending orders in one call.

```json
// Request — list of ERP order IDs
["S-00042", "S-00043", "S-00044"]

// Response
{ "imported": 3, "skipped": 0, "requested": 3 }
```

> Skipped orders are ones already imported or that fail validation. The call never fails entirely — it processes each order independently.

---

**GET `/api/admin/erp/clients`**  
Search clients in the connected ERP (Odoo) — used for the new delivery form autocomplete.

```
GET /api/admin/erp/clients?search=ahmed&limit=10
```

---

**GET `/api/admin/erp/products`**  
Search products in the connected ERP.

```
GET /api/admin/erp/products?search=colis&limit=10
```

---

## 9. Admin — Zones & Company

### Zones — `/api/v1/zones`

**GET `/api/v1/zones`** / **GET `/api/v1/zones/active`**  
List all zones / active zones only.

**POST `/api/v1/zones`**  
Create a delivery zone with a GeoJSON polygon.

```json
{
  "name": "Grand Tunis Nord",
  "city": "Tunis",
  "isActive": true,
  "polygon": { "type": "Polygon", "coordinates": [[[10.1, 36.8], ...]] }
}
```

**PUT `/api/v1/zones/{id}`** / **DELETE `/api/v1/zones/{id}`**  
Update / soft-delete a zone.

---

### Company — `/api/admin/companies`

**GET `/api/admin/companies/me`**  
Get the authenticated admin's company info.

**POST `/api/admin/companies/{id}/logo`**  
Upload the company logo (multipart/form-data). Used on BL and route PDFs.

---

## 10. Driver — Auth & Profile

All driver endpoints use a separate JWT issued by DriverService.

**GET `/api/driver/profile`** 🔒 Driver  
Get driver profile.

**PUT `/api/driver/profile`** 🔒 Driver  
Update driver name.

**POST `/api/driver/location`** 🔒 Driver  
Update driver's current GPS position. Called periodically by the mobile app.

```json
{ "lat": 36.8065, "lng": 10.1815 }
```

**POST `/api/driver/duty-status`** 🔒 Driver  
Toggle on-duty / off-duty status.

```json
{ "onDuty": true }
```

**PUT `/api/driver/fcm-token`** 🔒 Driver  
Register Firebase push notification token for the device.

```json
{ "token": "fcm_token_here" }
```

**GET `/api/driver/stats`** 🔒 Driver  
Get driver's own performance stats.

**GET `/api/driver/history`** 🔒 Driver  
Get driver's delivery history.

---

## 11. Driver — Deliveries

**Base path:** `/api/driver/deliveries`  
All require Driver JWT.

---

**GET `/api/driver/deliveries/available`**  
Get deliveries in the WAITING_DRIVER lane in the driver's city, ready to be accepted.

---

**GET `/api/driver/deliveries/active`**  
Get the driver's currently active delivery (one at a time).

---

**POST `/api/driver/deliveries/{id}/accept`**  
Accept a delivery. Atomic — returns 409 if another driver already accepted it.

---

**POST `/api/driver/deliveries/{id}/pickup`**  
Confirm package has been picked up from the depot.

---

**POST `/api/driver/deliveries/{id}/transit`**  
Start transit to the client's address.

```json
{ "lat": 36.8065, "lng": 10.1815 }
```

---

**POST `/api/driver/deliveries/{id}/pod`**  
Submit proof of delivery — completes the delivery.

```json
{
  "recipient": "Mohamed Ali",
  "signature": "base64_signature",
  "photos": ["base64_photo1", "base64_photo2"]
}
```

---

**POST `/api/driver/deliveries/{id}/fail`**  
Mark delivery as failed (client not home, refused, etc.).

```json
{ "failureCode": "CLIENT_ABSENT", "failureComment": "No answer after 3 calls" }
```

---

**POST `/api/driver/deliveries/{id}/cancel`**  
Cancel a delivery, returning it to the WAITING_DRIVER queue.

---

**PATCH `/api/driver/deliveries/{id}/cod`**  
Record COD cash collection result.

```json
{ "collected": true, "amount": 150.500 }
```

---

**GET `/api/driver/deliveries/{id}/handoff-token`**  
Generate a QR code token for parcel handoff to another driver.

---

**POST `/api/driver/deliveries/{id}/handoff`**  
Confirm receipt of a parcel from another driver using the QR token.

```json
{ "token": "..." }
```

---

**POST `/api/driver/deliveries/report-incident`**  
Submit a professional incident report with photos (accident, road block, etc.).

```json
{
  "incidentType": "ROAD_ACCIDENT",
  "description": "...",
  "latitude": 36.8,
  "longitude": 10.1,
  "photos": ["base64..."]
}
```

---

**GET `/api/driver/deliveries/{id}/bon-livraison`**  
Download the bon de livraison PDF. **No auth required** — shareable URL.

---

## 12. Driver — Routes

**Base path:** `/api/driver/routes`  
All require Driver JWT.

---

**GET `/api/driver/routes/today`**  
Get today's assigned route with all stops.

---

**POST `/api/driver/routes/{id}/start`**  
Start the route — changes status from VALIDATED to IN_PROGRESS.

---

**POST `/api/driver/routes/{id}/stops/{stopId}/arrive`**  
Mark arrival at a stop.

---

**GET `/api/driver/routes/{id}/pdf`**  
Download the route manifest PDF.

---

## 13. Public

**GET `/api/public/track/{deliveryId}`**  
Public delivery tracking — no authentication required. Returns current status, estimated delivery time, and driver position (if in transit).

> Share this URL with the end client so they can track their delivery.

---

## 14. Internal (Service-to-Service)

These endpoints are NOT exposed via the API Gateway. They are only called between microservices inside the Docker network.

**Header required:** `X-Internal-Secret: asm-internal-2026`

---

**GET `/internal/companies/{id}/erp-config`**  
Used by ErpAdapterService to get a company's ERP connection configuration (URL, credentials, type).

```json
// Response
{
  "erpType": "ODOO",
  "apiUrl": "http://odoo:8069/jsonrpc",
  "apiKey": "admin",
  "dbName": "DBTEST",
  "username": "admin",
  "uid": 2
}
```

---

## 15. ERP Adapter Service

**Base URL:** `http://erp-adapter:8088` (internal only — not exposed via API Gateway)

All calls require `X-Internal-Secret` header. Pass `X-Company-Id` header to scope to a specific company.

---

### Lookup — `/api/erp/lookup`

**GET `/api/erp/lookup/pending-orders`**  
Fetch orders confirmed in Odoo not yet exported.

**GET `/api/erp/lookup/pending-orders/{erpOrderId}`**  
Full order preview from Odoo.

**GET `/api/erp/lookup/clients`**  
Search customers in Odoo.

**GET `/api/erp/lookup/products`**  
Search products in Odoo.

---

### Sync — `/api/erp/sync`

**POST `/api/erp/sync/full-delivery`**  
Validate full delivery in Odoo stock (validate picking).

**POST `/api/erp/sync/partial-delivery`**  
Validate partial delivery in Odoo — creates a backorder for remaining items.

**POST `/api/erp/sync/order-cancellation`**  
Cancel the sale order in Odoo.

**POST `/api/erp/sync/failure`**  
Post a failure note on the Odoo order (client absent, refused, etc.).

---

## Error Responses

All endpoints return standard error format:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Delivery not found: uuid-here",
  "timestamp": "2026-05-13T10:00:00"
}
```

| Code | Meaning |
|---|---|
| 400 | Bad request — invalid input or business rule violation |
| 401 | Missing or expired token |
| 403 | Insufficient permissions |
| 404 | Resource not found |
| 409 | Conflict — e.g. order already imported, delivery already accepted |
| 503 | Downstream service unavailable (Odoo, ERP adapter) |

---

## Delivery Status Flow

```
UNSCHEDULED → SCHEDULED → PICKED_UP → IN_TRANSIT → DELIVERED
                                    ↘
                                     FAILED → (retry) → WAITING_DRIVER
                                            → RETURNED
```

| Status | Who sets it | Meaning |
|---|---|---|
| UNSCHEDULED | System (on import) | Delivery created, not yet assigned |
| SCHEDULED | Admin (assign) | Assigned to a driver |
| PICKED_UP | Driver | Package collected from depot |
| IN_TRANSIT | Driver | En route to client |
| DELIVERED | Driver (via POD) | Completed successfully |
| FAILED | Driver | Could not deliver |
| RETURNED | Driver | Package returned to depot |
| CANCELLED | Admin or Driver | Cancelled |

---

*Total endpoints documented: 120+ across 4 microservices.*
