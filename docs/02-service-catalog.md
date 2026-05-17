# 02 — Service Catalog

## Overview

| Service | Port | DB | Auth Exposed | Bounded Context |
|---------|------|----|--------------|-----------------|
| API Gateway | 80 | — | JWT validation via JWKS | Routing, CORS |
| **auth-server** | **8089** | postgres-app + postgres-driver | **OAuth2 token issuer** | **JWT issuance, JWKS** |
| AppBackend | 8080 | postgres-app (5435) | Cookie JWT (RSA) | User management, admin auth proxy |
| DeliveryMicroservice | 8082 | postgres-delivery (5434) | Bearer JWT (RSA) | Core delivery engine |
| DriverService | 8086 | postgres-driver (5437) | Bearer JWT (RSA) | Driver auth proxy, profiles, stats |
| ErpAdapterService | 8088 | H2 in-process | OAuth2 service token | Odoo RPC adapter |

---

## 2.1 API Gateway

**Technology:** Spring Cloud Gateway  
**Port:** 80  
**JWT Validation:** RSA public key fetched from `auth-server:8089/oauth2/jwks` at startup

### Route Predicates

| Route ID | Path Predicate | Target Service |
|----------|----------------|----------------|
| `app-backend-admin-auth` | `/api/auth/admin/**` | AppBackend :8080 |
| `driver-auth` | `/api/auth/driver/**` | DriverService :8086 |
| `delivery-admin-deliveries` | `/api/admin/deliveries/**` | DeliveryMicroservice :8082 |
| `delivery-admin-routes` | `/api/admin/routes/**` | DeliveryMicroservice :8082 |
| `delivery-admin-erp` | `/api/admin/erp/**` | DeliveryMicroservice :8082 |
| `delivery-admin-companies` | `/api/admin/companies/**` | DeliveryMicroservice :8082 |
| `delivery-admin-audit` | `/api/admin/audit/**` | DeliveryMicroservice :8082 |
| `app-backend-admin-users` | `/api/admin/users/**` | AppBackend :8080 |
| `delivery-ws` | `/ws/**` | DeliveryMicroservice :8082 |
| `delivery-driver-deliveries` | `/api/driver/deliveries/**` | DeliveryMicroservice :8082 |
| `delivery-driver-routes` | `/api/driver/routes/**` | DeliveryMicroservice :8082 |
| `driver-service` | `/api/driver/**` | DriverService :8086 |
| `delivery-orders` | `/api/orders/**` | DeliveryMicroservice :8082 |
| `delivery-deliveries` | `/api/deliveries/**` | DeliveryMicroservice :8082 |
| `delivery-public-tracking` | `/api/public/**` | DeliveryMicroservice :8082 |
| `internal-driver-service` | `/internal/drivers/**` | DriverService :8086 |
| `internal-app-backend` | `/internal/users/**` | AppBackend :8080 |

**CORS:** `http://localhost:*`, `http://127.0.0.1:*` — all methods allowed.

---

## 2.2 AppBackend

**Port:** 8080  
**Database:** `postgres-app` → table `admin_users`  
**Auth type:** Cookie-based JWT (HTTP-only `access_token` + `refresh_token` cookies)

### Entity: AdminUser

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `name` | VARCHAR 100 | |
| `email` | VARCHAR 100 UNIQUE | |
| `password_hash` | VARCHAR 255 | bcrypt |
| `role` | VARCHAR 20 | ADMIN, SUPER_ADMIN |
| `company_id` | UUID nullable | null = platform super-admin |
| `active` | BOOLEAN default true | |
| `created_at` | TIMESTAMP | immutable |

### JWT Claims (AppBackend tokens)

```
sub         → userId (UUID)
role        → "ADMIN" | "SUPER_ADMIN"
name        → display name
type        → "access" | "refresh"
companyId   → UUID string | null (null for SUPER_ADMIN)
```

Token expiry: access = 1 hour, refresh = 7 days.

### Endpoints

#### AdminAuthController — `/api/auth/admin` (PUBLIC)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/login` | email + password → tokens in HTTP-only cookies + body |
| POST | `/refresh` | reads `refresh_token` cookie → new tokens |
| POST | `/logout` | clears both cookies (sets expiry=past) |

#### AdminUserController — `/api/admin/users` (ADMIN or SUPER_ADMIN JWT required)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/` | Create admin user. Fire-and-forget audit POST to DeliveryMicroservice `/internal/audit` |
| GET | `/` | List users. Scoped to `companyId` from principal (null = all) |
| PATCH | `/{id}/status` | Toggle active/inactive |

#### InternalAdminUserController — `/internal/admin-users` (X-Internal-Secret)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/deactivate-by-company/{companyId}` | Deactivate all admins for a company |

### Key Config

| Property | Default | Env Var |
|----------|---------|---------|
| `server.port` | 8080 | — |
| `auth.client.id` | `app-backend` | `CLIENT_ID` |
| `auth.client.secret` | *(base64)* | `CLIENT_SECRET_APP` |
| `auth.server.url` | `http://auth-server:8089` | `AUTH_SERVER_URL` |
| `app.cookie.secure` | false | `COOKIE_SECURE` (set `true` in prod HTTPS) |
| `delivery.service.url` | `http://delivery-service:8082` | `DELIVERY_SERVICE_URL` |

---

## 2.3 DeliveryMicroservice

**Port:** 8082  
**Database:** `postgres-delivery` — 29 Flyway migrations  
**Auth type:** OAuth2 client_credentials (token validated via auth-server JWKS)  
**Bounded contexts:** Delivery, Order, Route, Dispatch, ERP, Outbox, WebSocket, SLA, Storage

### Key Entities

#### `orders` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `company_id` | UUID | Multi-tenant FK |
| `source` | VARCHAR | ODOO, APP |
| `erp_order_id` | VARCHAR | Odoo sale.order name (e.g., S00042) |
| `erp_external_ref` | VARCHAR | External reference |
| `client_name`, `client_phone` | VARCHAR | |
| `dropoff_lat`, `dropoff_lng` | DECIMAL(10,7) | |
| `odoo_sync_status` | VARCHAR | SYNCED, PENDING_SYNC |
| `odoo_backorder_id` | INTEGER | Odoo picking ID for backorder delivery |
| `items` | JSONB | Array of {sku, name, quantity, price} |
| `schema_version` | VARCHAR default '1.0.0' | |
| UNIQUE | `(erp_order_id, company_id)` | V22 migration |

#### `deliveries` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `company_id` | UUID | |
| `order_id` | UUID UNIQUE | 1:1 with orders |
| `driver_id` | UUID nullable | |
| `status` | VARCHAR | See state machine in doc 04 |
| `failure_code` | VARCHAR | CLIENT_ABSENT, REFUSED, WRONG_ADDRESS, DAMAGED, OTHER |
| `cod_amount`, `cod_collected` | DECIMAL | Cash-on-delivery |
| `route_geometry` | TEXT | GeoJSON from OSRM |
| `transit_sla_minutes_computed` | INTEGER | OSRM × 1.20 + 8 min buffer |
| `version` | BIGINT | Optimistic locking (V25 migration) |

#### `route_stops` table

| Column | Notes |
|--------|-------|
| `handoff_token` | QR code for stop handoff between drivers |
| `handoff_required` | Boolean |
| `sla_status` | ON_TIME, EARLY, LATE |
| `removed_at`, `removed_reason`, `removed_by` | Stop cancellation audit |
| `drive_duration_seconds`, `drive_distance_meters` | From OSRM |

#### `outbox_event` table

| Column | Notes |
|--------|-------|
| `id` | UUID PK |
| `event_type` | ERP_SYNC_STOCK, ERP_SYNC_FAILURE, ERP_SYNC_CANCELLATION, INCREMENT_DRIVER_STAT |
| `payload` | TEXT (JSON) |
| `status` | PENDING → PROCESSING → PROCESSED \| FAILED |
| `retry_count` | INT default 0 |
| `last_error` | TEXT |
| INDEX | `(status, created_at)` |

#### `proof_of_delivery` table

| Column | Notes |
|--------|-------|
| `delivery_id` | UUID UNIQUE |
| `signature_url` | MinIO path |
| `photo_url` | MinIO path |
| `bon_livraison_photo_url` | MinIO path |
| `lat`, `lng` | DECIMAL(10,7) — GPS at POD capture |

#### `processed_requests` table (V26)
Idempotent request deduplication. Keyed by idempotency header value. Cleaned up periodically.

### Scheduled Jobs

| Job | Interval | Description |
|-----|----------|-------------|
| `OutboxProcessor.processOutbox()` | 20s (fixedDelay) | Claim + process outbox events, SKIP LOCKED |
| `SlaMonitoringService.checkSlaStatuses()` | 30s (configurable) | Detect SLA breaches, publish WebSocket alerts |
| `ErpAutoImportNotifier.checkForNewOrders()` | 120s (configurable) | Poll ERP adapter for new orders, WebSocket notify |
| `ProcessedRequestCleanupJob` | ~1h | Purge old idempotency records |

### Key Config

| Property | Default | Env Var |
|----------|---------|---------|
| `server.port` | 8082 | — |
| `auth.client.id` | `delivery-service` | `CLIENT_ID` |
| `auth.client.secret` | *(base64)* | `CLIENT_SECRET_DELIVERY` |
| `auth.server.jwks-uri` | `http://auth-server:8089/oauth2/jwks` | `AUTH_SERVER_JWKS_URI` |
| `spring.rabbitmq.host` | `localhost` | `RABBITMQ_HOST` |
| `websocket.broker.relay.enabled` | true | `WEBSOCKET_BROKER_RELAY_ENABLED` |
| `app.security.trust-gateway-headers` | false | — |
| `routing.osrm.enabled` | false | `ROUTING_OSRM_ENABLED` |
| `routing.osrm.base-url` | `http://osrm:5000` | `ROUTING_OSRM_BASE_URL` |
| `routing.transit-sla.multiplier` | 1.20 | `ROUTING_TRANSIT_SLA_MULTIPLIER` |
| `routing.transit-sla.buffer-minutes` | 8 | `ROUTING_TRANSIT_SLA_BUFFER_MINUTES` |
| `routing.transit-sla.min-minutes` | 15 | `ROUTING_TRANSIT_SLA_MIN_MINUTES` |
| `routing.transit-sla.max-minutes` | 240 | `ROUTING_TRANSIT_SLA_MAX_MINUTES` |
| `erp.adapter-url` | `http://erp-adapter:8088` | `ERP_ADAPTER_URL` |
| `erp.default-provider` | odoo | `ERP_DEFAULT_PROVIDER` |
| `minio.url` | `http://minio:9000` | `MINIO_URL` |
| `minio.public-url` | `http://localhost:9000` | `MINIO_PUBLIC_URL` |
| `fcm.enabled` | false | `FCM_ENABLED` |
| `driver.service.url` | `http://driver-service:8086` | `DRIVER_SERVICE_URL` |
| `outbox.alert.webhook-url` | (empty) | `OUTBOX_ALERT_WEBHOOK_URL` |
| `app.ops.sla.waiting-limit-minutes` | 15 | `OPS_SLA_WAITING_LIMIT` |
| `app.ops.sla.assign-limit-minutes` | 20 | `OPS_SLA_ASSIGN_LIMIT` |
| `app.ops.sla.pickup-limit-minutes` | 20 | `OPS_SLA_PICKUP_LIMIT` |

---

## 2.4 DriverService

**Port:** 8086  
**Database:** `postgres-driver`  
**Auth type:** Bearer JWT (drivers), X-Internal-Secret (internal callers)

### Entity: Driver

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `name` | VARCHAR 100 | |
| `phone` | VARCHAR 20 UNIQUE | Login identifier |
| `password_hash` | VARCHAR | bcrypt |
| `current_lat`, `current_lng` | DECIMAL(10,7) | |
| `last_location_at` | TIMESTAMP | |
| `active` | BOOLEAN default true | |
| `fcm_token` | VARCHAR 500 nullable | Device push token |

### JWT Claims (DriverService tokens)

```
sub     → driverId (UUID)
role    → "DRIVER"
name    → driver name
phone   → phone number
type    → "access" | "refresh"
```

> **No `companyId` claim.** Drivers are a shared pool across all tenant companies.  
> Token expiry: access = **24 hours**, refresh = 7 days.

### Endpoints

#### DriverAuthController — `/api/auth/driver` (PUBLIC)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/register` | Phone + password registration |
| POST | `/login` | Returns `accessToken`, `refreshToken`, driver info |
| POST | `/refresh-token` | Rotate tokens |
| PUT | `/change-password` | Requires valid access token |

#### DriverController — `/api/driver` (DRIVER role required)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/profile` | Driver profile |
| PUT | `/profile` | Update name |
| POST | `/location` | Update lat/lng |
| GET | `/stats` | Delivery counters |
| GET | `/history` | Status history per delivery |
| PUT | `/fcm-token` | Store FCM token `{fcmToken}` |
| DELETE | `/fcm-token` | Clear FCM token |
| POST | `/duty-status` | Toggle on/off duty |

#### AdminDriverController — `/api/admin/drivers` (SUPER_ADMIN)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | List all drivers |
| GET | `/{id}` | Get driver |
| POST | `/` | Create driver account |
| PUT | `/{id}` | Update driver |
| PATCH | `/{id}/status` | Set active/inactive |
| POST | `/{id}/reset-password` | Reset password |

#### InternalDriverController — `/internal/drivers` (X-Internal-Secret, hidden from Swagger)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/available` | Active drivers (called by DeliveryMicroservice for dispatch) |
| GET | `/{id}` | Driver detail including `fcmToken` |
| GET | `/batch` | Batch fetch by ID list |
| PUT | `/{id}/location` | Update driver current location |
| POST | `/{id}/stats/increment` | Increment stat counter (delivered/failed/cancelled) |

### Key Config

| Property | Default | Env Var |
|----------|---------|---------|
| `server.port` | 8086 | — |
| `spring.jpa.hibernate.ddl-auto` | update | — |
| `auth.client.id` | `driver-service` | `CLIENT_ID` |
| `auth.client.secret` | *(base64)* | `CLIENT_SECRET_DRIVER` |
| `auth.server.jwks-uri` | `http://auth-server:8089/oauth2/jwks` | `AUTH_SERVER_JWKS_URI` |

---

## 2.5 ErpAdapterService

**Port:** 8088  
**Database:** H2 in-process (idempotency table only)  
**Auth type:** X-Internal-Secret on all endpoints (no JWT)

### Architecture

```
DeliveryMicroservice
  └── POST /api/erp/sync/** + X-Company-Id header
        └── ErpAdapterService
              ├── CompanyConfigResolver → resolves Odoo URL + credentials by companyId
              ├── CompanyAdapterFactory → selects adapter implementation
              │     ├── OdooSyncAdapter     ← IMPLEMENTED
              │     ├── DuxSyncAdapter      ← STUB (returns false, do not use)
              │     └── NoopSyncAdapter     ← safe fallback
              ├── IdempotencyService        ← H2 idempotent_transaction
              └── ConcurrentHashSet inFlight ← prevents concurrent order sync
```

### ERP Provider Ports (interfaces)

```java
ErpSyncPort {
  boolean syncOrderCancellation(erpOrderId, txId)
  boolean syncFullDelivery(erpOrderId, backorderPickingId, txId)
  ErpPartialDeliveryResultDTO syncPartialDelivery(erpOrderId, items[], txId)
  boolean syncFailure(erpOrderId, failureCode, comment, txId)
}

ErpOrderPort {
  String resolveOrderId(erpOrderRef)
  String getOrderReference(erpOrderId)
}

ErpLookupPort {
  List<ErpClientDTO>              searchClients(search, limit)
  List<ErpProductDTO>             searchProducts(search, limit)
  List<ErpPendingOrderSummaryDTO> getPendingOrders(limit)
  ErpPendingOrderPreviewDTO       getPendingOrderPreview(erpOrderId)
}
```

### Endpoints

#### ErpSyncController — `/api/erp/sync` (X-Internal-Secret)

| Method | Path | Body / Params | Returns |
|--------|------|---------------|---------|
| POST | `/order-cancellation` | params: erpOrderId, transactionId | `{success: boolean}` |
| POST | `/full-delivery` | `{erpOrderId, backorderPickingId?, transactionId}` | `{success: boolean}` |
| POST | `/partial-delivery` | `{erpOrderId, items[], transactionId}` | `ErpPartialDeliveryResultDTO` |
| POST | `/failure` | `{erpOrderId, failureCode, comment, transactionId}` | `{success: boolean}` |

#### ErpOrderController — `/api/erp/orders` (X-Internal-Secret)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/resolve` | erpOrderRef → integer Odoo ID |
| GET | `/{erpOrderId}/reference` | integer Odoo ID → human-readable name |

#### ErpLookupController — `/api/erp/lookup` (X-Internal-Secret)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/clients` | params: search, limit (1-50) |
| GET | `/products` | params: search, limit (1-50) |
| GET | `/pending-orders` | params: limit (1-300) |
| GET | `/pending-orders/{erpOrderId}` | Full order preview |

### Odoo JSON-RPC Client

- **URL pattern:** `${ODOO_URL}/jsonrpc`
- **Protocol:** JSON-RPC 2.0 `execute_kw`
- **Auth:** Credentials passed in every call body `[db, uid, password, model, method, args, kwargs]`
- **Timeouts:** connect 5s (`ODOO_CONNECT_TIMEOUT_MS`), read 15s (`ODOO_READ_TIMEOUT_MS`)

### Odoo Field Semantics (critical for Odoo 17/18/19)

| Field | Model | Meaning in Odoo 18+ |
|-------|-------|---------------------|
| `stock.move.line.quantity` | stock.move.line | **Done quantity** (replaces deprecated `qty_done`) |
| `stock.move.quantity` | stock.move | **Demand quantity** — do NOT write done qty here |
| `sale.order.line.qty_delivered` | sale.order.line | Delivered qty — manually synced after picking validation |

> Writing done qty to `stock.move.quantity` changes the demand, which makes Odoo see 100% complete
> and skip the backorder wizard. Always write to `stock.move.line.quantity`.

### Idempotency: H2 `idempotent_transaction` Table

| Column | Notes |
|--------|-------|
| `transaction_id` | String PK — UUID from caller |
| `erp_order_id` | String — Odoo sale.order ref |
| `status` | SUCCESS \| FAILED |
| `response_payload` | VARCHAR 2000 — JSON result |
| `processed_at` | TIMESTAMP |

**Behavior:**
- First call with new `transactionId`: execute → cache result (success only)
- Retry with same `transactionId`: return cached result immediately
- Failed operations are NOT cached → next retry re-executes against Odoo
- Null/blank `transactionId`: bypass cache with warning log

### Key Config

| Property | Default | Env Var |
|----------|---------|---------|
| `server.port` | 8088 | — |
| `odoo.url` | `http://odoo:8069/jsonrpc` | `ODOO_URL` |
| `odoo.db` | odoo | `ODOO_DB` |
| `odoo.uid` | 1 | `ODOO_UID` |
| `odoo.password` | admin | `ODOO_PASSWORD` |
| `odoo.timeout.connect-ms` | 5000 | `ODOO_CONNECT_TIMEOUT_MS` |
| `odoo.timeout.read-ms` | 15000 | `ODOO_READ_TIMEOUT_MS` |
| `auth.client.id` | `erp-adapter` | `CLIENT_ID` |
| `auth.client.secret` | *(base64)* | `CLIENT_SECRET_ERP` |
| `auth.server.jwks-uri` | `http://auth-server:8089/oauth2/jwks` | `AUTH_SERVER_JWKS_URI` |
