# ASM Tracking — Full Project Context
> Version: 5.0 | March 2026
> Read this completely before touching any code.
> Single source of truth for the entire platform.

---

## PS — Two Critical Rules

### Orders
There is ONE and only ONE source of orders:
  Confirmed sale.order in Odoo
  Dispatcher imports via /import page in dashboard
  That is it. Nothing else.

### Clients
Clients are NOT managed in this platform.
Clients exist only in the ERP (Odoo res.partner).
Our system stores erp_client_id as a reference only.
We never create or manage client records.
Future Flutter client app = read-only view of ERP data. Ignore for now.

---

## 1. What Is ASM Tracking

ASM Tracking is a SaaS delivery management platform.
Each company gets their own deployed instance.
ERP-agnostic: plug any ERP via ErpPort interface.



---

## 2. Core Philosophy

```
ERP = source of truth for everything business
      (clients, products, orders, invoices, stock)

Our platform = operational execution layer only
               (deliveries, drivers, POD, dispatch, tracking)

ERP agnostic: change ERP_TYPE env var to switch ERP
Transport: internal drivers only
```

---

## 3. Architecture

```
Admin Web Dashboard (Next.js :3000)
Flutter Driver App
          |
     API Gateway :80
     (JWT + RBAC + routing)
          |
  --------+-----------
  |                  |
App Backend      Delivery Service
:8080            :8082
(admin accounts) (core operations)
                      |
                 Driver Service
                 :8086
                 (driver management)
                      |
       +--------------+------------------+
       |    ERP Adapter Layer            |
       |    (inside Delivery Service)    |
       |    future: separate :8085       |
       |                                 |
       |    ErpPort (interface)          |
       |    OdooAdapter  DuxAdapter      |
       |    (active)     (stub/future)   |
       +--------------+------------------+
                      |
                 Odoo ERP :8069
                 (only ERP today)
                      |
                 MinIO :9000
                 (POD file storage)
```

### Services

| Service | Port | Responsibility |
|---------|------|----------------|
| API Gateway | 80 | Single entry, JWT, RBAC, routing |
| App Backend | 8080 | Admin/dispatcher/manager accounts only |
| Delivery Service | 8082 | Deliveries, tournees, POD, ERP adapter |
| Driver Service | 8086 | Driver auth, profile, GPS, stats |
| Admin Dashboard | 3000 | Dispatch, import, tournees, map, KPIs |
| MinIO | 9000 | POD file storage |
| PostgreSQL | per service | delivery_db + app_db + driver_db |
| Odoo | 8069 | ERP (self-hosted, source of all orders) |

### ERP Adapter Now vs Future

```
NOW (inside Delivery Service):
  ErpPort.java         interface for all ERP operations
  OdooAdapter.java     implements ErpPort for Odoo JSON-RPC
  DuxAdapter.java      stub, implements ErpPort for DUX (future)
  ErpAdapterFactory    returns correct adapter from ERP_TYPE env var

FUTURE (separate microservice :8085):
  same code extracted to standalone Spring Boot service
  Delivery Service calls via REST
  zero business logic change
  benefit: independent scaling + deployment
```

---

## 4. RBAC

### Roles
```
ADMIN       full access to everything
DISPATCHER  deliveries, tournees, import ERP, assign drivers
MANAGER     read only: stats, reports, KPIs
DRIVER      driver app only: assigned deliveries, POD, GPS
CLIENT      future only, ignore for now
```

### JWT

Admin/Dispatcher/Manager (issued by App Backend):
```json
{ "sub": "uuid", "role": "ADMIN|DISPATCHER|MANAGER", "name": "...", "email": "..." }
```

Driver (issued by Driver Service):
```json
{ "sub": "uuid", "role": "DRIVER", "name": "...", "phone": "..." }
```

All services share: JWT_SECRET=asmsecret2026

### Gateway Route Protection

| Route | Roles |
|-------|-------|
| /api/auth/** | PUBLIC |
| /api/dev/** | PUBLIC |
| /api/admin/stats | ADMIN, DISPATCHER, MANAGER |
| /api/admin/reports/** | ADMIN, DISPATCHER, MANAGER |
| /api/admin/** | ADMIN, DISPATCHER |
| /api/deliveries/** | ALL authenticated |
| /api/driver/deliveries/** | DRIVER -> Delivery Service |
| /api/driver/** | DRIVER -> Driver Service |
| /internal/** | BLOCKED always |

Gateway injects into every downstream request:
```
X-User-Id, X-User-Role, X-User-Name
```

---

## 5. App Backend (:8080)

Manages dashboard user accounts only.
No client management. No client accounts.

### DB (app_db)
```sql
admin_users:
  id UUID PK, name VARCHAR, email VARCHAR UNIQUE,
  password_hash VARCHAR, role VARCHAR (ADMIN|DISPATCHER|MANAGER),
  active BOOLEAN DEFAULT true, created_at TIMESTAMP
```

### Endpoints
```
POST   /api/auth/admin/login          { token, refreshToken, user }
POST   /api/auth/admin/refresh-token  { token }
GET    /api/admin/users               ADMIN only
POST   /api/admin/users               ADMIN only
PUT    /api/admin/users/{id}          ADMIN only
DELETE /api/admin/users/{id}          ADMIN only
```

Default seed on startup:
```
email: admin@asm-delivery.com / password: Admin@2026 / role: ADMIN
```

---

## 6. The Only Workflow

```
STEP 1 — Employee or odoo portail client (we will fetch orders from db) confirms sale.order in Odoo

STEP 2 — Dispatcher opens /import in dashboard
         Sees confirmed Odoo orders not yet in system
         Clicks "Importer", selects COD or PREPAID
         System creates Order + Delivery (WAITING_DRIVER)
         source: ODOO, erpOrderId saved

STEP 3 — Dispatcher creates tournee
         Groups deliveries by zone (governorat)
         Selects driver + vehicle + date
         Orders stops logically by address
         Validates tournee -> driver receives it

STEP 4 — Driver opens app
         Sees today's tournee with ordered stops
         For each stop:
           Navigate -> openstreetmap
           Arrive -> tap ARRIVED
           Deliver -> POD (signature + photo)
           OR Fail -> reason + comment
           OR Partial -> quantities per item
           Next stop

STEP 5 — On DELIVERED:
         syncStockUpdate:
           Odoo transfer validated (stock out)
           Invoice created
           PREPAID -> payment registered auto
           COD -> invoice stays open (accountant handles)

STEP 6 — All stops done -> tournee CLOSED
         Dispatcher exports reports (PDF + Excel)
```

### Key Rules
```
Orders come ONLY from Odoo ERP import
No manual order creation
No client app order creation
Dispatcher/Admin assigns drivers (never auto-assigned)
Driver executes only what is assigned
ERP always stays in sync after execution
```

---

## 7. ERP Adapter Layer

### ErpPort Interface
```java
interface ErpPort {
  // Import
  List<ErpPendingOrder> getPendingOrders();
  ErpPendingOrder getOrderDetails(String erpOrderId);

  // Search (for dashboard UI)
  List<ErpClientDTO> searchClients(String query);
  List<ErpProductDTO> searchProducts(String query);

  // Sync back to ERP (after execution)
  boolean validateTransfer(String erpOrderId, List<ErpOrderItem> items);
  String createInvoice(String erpOrderId);
  boolean registerPayment(String invoiceId);
  boolean cancelOrder(String erpOrderId);
  boolean addNote(String erpOrderId, String note);
}
```

### OdooSyncService
```
syncStockUpdate(order, deliveredItems):
  ErpPort.validateTransfer(erpOrderId, deliveredItems)
  invoiceId = ErpPort.createInvoice(erpOrderId)
  if PREPAID -> ErpPort.registerPayment(invoiceId)
  if COD -> leave invoice open

syncCancellation(order):
  if erpOrderId null -> log + skip
  ErpPort.cancelOrder(erpOrderId)

syncFailure(order, failureCode, comment):
  if erpOrderId null -> log + skip
  ErpPort.addNote(erpOrderId, "Echec: {failureCode} - {comment}")
```

Note: NO syncOrderCreation.
Orders already exist in Odoo. We only sync back execution results.

### Factory
```
ERP_TYPE=odoo  -> OdooAdapter (active)
ERP_TYPE=dux   -> DuxAdapter (stub, future)
ERP_TYPE=mock  -> MockAdapter (testing)
```

### Odoo Config
```
ODOO_URL: http://host.docker.internal:8069/jsonrpc
ODOO_DB: odoo
ODOO_UID: 2
ODOO_PASSWORD: admin
Local: 192.168.10.75:8069
```

IMPORTANT: Odoo returns false not null for empty fields.
Always: value instanceof Boolean ? null : value

---

## 8. Delivery Status Machine

```
WAITING_DRIVER
      | dispatcher assigns (via tournee or direct)
  ASSIGNED
      | driver picks up package
  PICKED_UP
      | driver starts moving to client
  IN_TRANSIT
      | POD collected        | some items only
  DELIVERED              PARTIAL

WAITING_DRIVER -> CANCELLED (dispatcher cancels)
ASSIGNED       -> CANCELLED (dispatcher, driver released)
PICKED_UP+     -> FAILED (driver reports)
PICKED_UP+     -> PARTIAL (driver reports partial)
```

### ERP Sync Per Status
```
DELIVERED -> syncStockUpdate (full quantities delivered)
PARTIAL   -> syncStockUpdate (actual quantities per item) and a backorder
CANCELLED -> syncCancellation (Odoo order cancelled)
FAILED    -> syncFailure (note added to Odoo order)
```

---

## 9. Tournees (Routes) — Core Docx Requirement

Docx 3.1 point 3: "Constitution des tournees"
Docx 4.1.1: "CRUD livraisons et tournees"

### Concept
```
Tournee = one driver's full day delivery route
          grouped by zone (Tunisian governorat)
          ordered stops for maximum efficiency

Dispatcher creates tournee:
  select driver + vehicle + date + zone
  add deliveries (from WAITING_DRIVER list)
  order stops logically
  validate -> driver receives in app

Driver sees:
  "Tournee du jour - 5 stops"
  Stop 1, Stop 2, ... in fixed order
  No choosing, no reordering
```

### DB Tables
```sql
routes:
  id UUID PK DEFAULT gen_random_uuid()
  name VARCHAR NOT NULL
  driver_id UUID NOT NULL
  vehicle_id UUID FK -> vehicles
  date DATE NOT NULL
  zone VARCHAR
  status VARCHAR DEFAULT 'DRAFT'
  created_by UUID NOT NULL
  created_at TIMESTAMP DEFAULT NOW()
  validated_at TIMESTAMP
  closed_at TIMESTAMP

route_stops:
  id UUID PK DEFAULT gen_random_uuid()
  route_id UUID FK -> routes NOT NULL
  delivery_id UUID FK -> deliveries NOT NULL
  stop_order INTEGER NOT NULL
  status VARCHAR DEFAULT 'PENDING'
  arrived_at TIMESTAMP
  completed_at TIMESTAMP
  notes TEXT
```

### Status Machines
```
Route:      DRAFT -> VALIDATED -> IN_PROGRESS -> CLOSED
Stop:       PENDING -> ARRIVED -> COMPLETED | FAILED | PARTIAL
```

### Admin Endpoints
```
GET    /api/admin/routes
GET    /api/admin/routes/{id}
POST   /api/admin/routes
PUT    /api/admin/routes/{id}
DELETE /api/admin/routes/{id}              DRAFT only
POST   /api/admin/routes/{id}/stops        add delivery
DELETE /api/admin/routes/{id}/stops/{id}   remove delivery
PUT    /api/admin/routes/{id}/stops/reorder
PUT    /api/admin/routes/{id}/validate     DRAFT -> VALIDATED
POST   /api/admin/routes/{id}/close        -> CLOSED
GET    /api/admin/routes/{id}/pdf          feuille de route PDF
```

### Driver Endpoints
```
GET    /api/driver/routes/today
GET    /api/driver/routes/{id}
POST   /api/driver/routes/{id}/start                   VALIDATED -> IN_PROGRESS
POST   /api/driver/routes/{id}/stops/{stopId}/arrive   -> ARRIVED
```

---

## 10. Vehicles

```sql
vehicles:
  id UUID PK DEFAULT gen_random_uuid()
  name VARCHAR NOT NULL
  plate VARCHAR UNIQUE NOT NULL
  type VARCHAR NOT NULL   -- TRUCK | VAN | CAR | MOTO
  driver_id UUID
  active BOOLEAN DEFAULT true
  created_at TIMESTAMP DEFAULT NOW()
```

### Endpoints
```
GET    /api/admin/vehicles
POST   /api/admin/vehicles
PUT    /api/admin/vehicles/{id}
DELETE /api/admin/vehicles/{id}
PUT    /api/admin/vehicles/{id}/assign   body: { driverId }
```

---

## 11. Zones

Fixed constant list (not in DB):
```
Tunis, Ariana, Ben Arous, Manouba, Nabeul, Zaghouan,
Bizerte, Beja, Jendouba, Kef, Siliana, Sousse,
Monastir, Mahdia, Sfax, Kairouan, Kasserine,
Sidi Bouzid, Gabes, Medenine, Tataouine, Gafsa, Tozeur, Kebili
```

---

## 12. Exception Handling

### Failure Codes
```java
enum FailureCode {
  CLIENT_ABSENT, REFUSED, WRONG_ADDRESS, DAMAGED, OTHER
}
```

### Cancellation Rules
```
WAITING_DRIVER -> can cancel -> syncCancellation
ASSIGNED       -> can cancel -> release driver -> syncCancellation
PICKED_UP+     -> cannot cancel -> must FAIL or PARTIAL
```

### Partial Delivery
```
POST /api/driver/deliveries/{id}/partial
body: { deliveredItems: [{ itemId, quantityDone }], comment }
  -> update items JSONB with quantityDone per item
  -> status = PARTIAL
  -> driver available = true (released for next tournee)
  -> syncStockUpdate called with actual quantities
```

---

## 13. POD

Storage: MinIO (files only, no base64 in DB)
```
path: pod/{deliveryId}/signature-{timestamp}.png
      pod/{deliveryId}/photo-{timestamp}.png
DB:   signature_url VARCHAR, photo_url VARCHAR
```

```sql
proof_of_delivery:
  id UUID PK DEFAULT gen_random_uuid()
  delivery_id UUID UNIQUE FK -> deliveries NOT NULL
  signature_url VARCHAR(500) NOT NULL
  photo_url VARCHAR(500)
  comment TEXT
  collected_at TIMESTAMP DEFAULT NOW()
  lat DECIMAL(10,7)
  lng DECIMAL(10,7)
```

---

## 14. Full Database Schema

### delivery_db
```sql
orders:
  id, source VARCHAR DEFAULT 'ODOO',
  erp_order_id VARCHAR UNIQUE, erp_external_ref VARCHAR,
  erp_client_id VARCHAR, client_name VARCHAR, client_phone VARCHAR,
  dropoff_address TEXT, dropoff_city VARCHAR,
  dropoff_postal_code VARCHAR, dropoff_country_code DEFAULT 'TN',
  dropoff_lat DECIMAL, dropoff_lng DECIMAL,
  delivery_instructions TEXT, total_amount DECIMAL,
  currency DEFAULT 'TND', payment_type VARCHAR,
  amount_to_collect DECIMAL DEFAULT 0,
  scheduled_at TIMESTAMP, priority DEFAULT 'NORMAL',
  items JSONB, total_quantity INTEGER, total_weight_kg DECIMAL,
  status DEFAULT 'PENDING', odoo_sync_status DEFAULT 'SYNCED',
  created_at TIMESTAMP, updated_at TIMESTAMP

deliveries:
  id, order_id UUID UNIQUE FK,
  driver_id UUID, route_stop_id UUID FK,
  status DEFAULT 'WAITING_DRIVER',
  failure_code VARCHAR, fail_reason TEXT,
  cancel_reason TEXT, cancelled_by VARCHAR,
  assigned_at, picked_up_at, in_transit_at,
  completed_at, failed_at, cancelled_at,
  created_at, updated_at

routes:
  id, name, driver_id, vehicle_id FK,
  date DATE, zone VARCHAR,
  status DEFAULT 'DRAFT', created_by,
  created_at, validated_at, closed_at

route_stops:
  id, route_id FK, delivery_id FK,
  stop_order INTEGER, status DEFAULT 'PENDING',
  arrived_at, completed_at, notes TEXT

vehicles:
  id, name, plate UNIQUE, type,
  driver_id, active DEFAULT true, created_at

proof_of_delivery:
  id, delivery_id UNIQUE FK,
  signature_url NOT NULL, photo_url,
  comment, collected_at, lat, lng

delivery_status_history:
  id, delivery_id FK, status,
  changed_by, changed_by_role, note, changed_at

tracking:
  id, delivery_id FK, lat, lng, timestamp
```

### driver_db
```sql
drivers:
  id, name, phone UNIQUE, password_hash,
  available DEFAULT true, current_lat, current_lng,
  last_location_at, active DEFAULT true, created_at

driver_otp:
  id, phone, code, expires_at, used, created_at

driver_stats:
  id, driver_id UNIQUE FK,
  total_deliveries, delivered, failed,
  cancelled, partial, updated_at
```

### app_db
```sql
admin_users:
  id, name, email UNIQUE, password_hash,
  role (ADMIN|DISPATCHER|MANAGER),
  active DEFAULT true, created_at
```

---

## 15. All API Endpoints

### Delivery Service (:8082)

Admin Deliveries:
```
GET  /api/admin/deliveries           ?status,driverId,date,zone,page,size
GET  /api/admin/deliveries/{id}
GET  /api/admin/deliveries/{id}/pod
GET  /api/admin/deliveries/{id}/pdf  bon de livraison PDF
GET  /api/admin/deliveries/stats
GET  /api/admin/deliveries/drivers
PUT  /api/admin/deliveries/{id}/assign   { driverId }
POST /api/admin/deliveries/{id}/cancel
```

Admin ERP Import:
```
GET  /api/admin/erp/pending-orders
GET  /api/admin/erp/pending-orders/{erpOrderId}
POST /api/admin/erp/import-order/{erpOrderId}
GET  /api/admin/erp/clients?search=
GET  /api/admin/erp/products?search=
```

Admin Routes:
```
GET    /api/admin/routes
GET    /api/admin/routes/{id}
POST   /api/admin/routes
PUT    /api/admin/routes/{id}
DELETE /api/admin/routes/{id}
POST   /api/admin/routes/{id}/stops
DELETE /api/admin/routes/{id}/stops/{stopId}
PUT    /api/admin/routes/{id}/stops/reorder
PUT    /api/admin/routes/{id}/validate
POST   /api/admin/routes/{id}/close
GET    /api/admin/routes/{id}/pdf
```

Admin Vehicles:
```
GET    /api/admin/vehicles
POST   /api/admin/vehicles
PUT    /api/admin/vehicles/{id}
DELETE /api/admin/vehicles/{id}
PUT    /api/admin/vehicles/{id}/assign
```

Admin Reports:
```
GET /api/admin/reports/kpi?from=&to=
GET /api/admin/reports/excel?from=&to=
```

Deliveries (read):
```
GET /api/deliveries/{id}
GET /api/deliveries/{id}/tracking
GET /api/deliveries/{id}/history
GET /api/deliveries/{id}/pod
```

Driver Deliveries:
```
GET  /api/driver/deliveries/active
GET  /api/driver/deliveries/{id}
POST /api/driver/deliveries/{id}/pickup
POST /api/driver/deliveries/{id}/transit
POST /api/driver/deliveries/{id}/pod
POST /api/driver/deliveries/{id}/fail
POST /api/driver/deliveries/{id}/partial
POST /api/driver/deliveries/{id}/report
POST /api/driver/location
```

Driver Routes:
```
GET  /api/driver/routes/today
GET  /api/driver/routes/{id}
POST /api/driver/routes/{id}/start
POST /api/driver/routes/{id}/stops/{stopId}/arrive
```

Dev (remove in production):
```
POST /api/dev/driver-token
```

### Driver Service (:8086)

Auth (public):
```
POST /api/auth/driver/register
POST /api/auth/driver/login
POST /api/auth/driver/refresh-token
```

Driver (DRIVER role):
```
GET  /api/driver/profile
PUT  /api/driver/profile
PUT  /api/driver/password
PUT  /api/driver/availability
GET  /api/driver/stats
GET  /api/driver/history
POST /api/driver/location
```

Internal (header: X-Internal-Secret: asm-internal-2026):
```
GET  /internal/drivers/available
GET  /internal/drivers/{id}
PUT  /internal/drivers/{id}/availability
PUT  /internal/drivers/{id}/location
POST /internal/drivers/{id}/stats/increment
```

### App Backend (:8080)
```
POST   /api/auth/admin/login
POST   /api/auth/admin/refresh-token
GET    /api/admin/users        ADMIN only
POST   /api/admin/users        ADMIN only
PUT    /api/admin/users/{id}   ADMIN only
DELETE /api/admin/users/{id}   ADMIN only
```

---

## 16. Admin Dashboard Pages

| Page | Route | Access |
|------|-------|--------|
| Login | /login | PUBLIC |
| Dashboard | /dashboard | ALL |
| Import ERP | /import | ADMIN, DISPATCHER |
| Deliveries | /deliveries | ALL |
| Routes | /routes | ADMIN, DISPATCHER |
| Live Map | /map | ALL |
| Drivers | /drivers | ADMIN, DISPATCHER |
| Reports | /reports | ALL |
| Settings | /settings | ADMIN |

RBAC in UI:
```
ADMIN:      everything visible and active
DISPATCHER: everything except /settings admin users section
MANAGER:    read only, no action buttons, can export
```

---

## 17. Driver App Screens

| Screen | Description |
|--------|-------------|
| Login | Phone + password |
| Today's Route | Ordered stops for assigned tournee |
| Stop Detail | Client name + address + items + Navigate button |
| Navigation | Deep link to Google Maps or Waze |
| POD | Signature pad + camera + comment |
| Failure | Reason enum + comment |
| Partial | Per-item quantity + comment |
| Profile | Name + availability toggle + stats |

### Driver Flow
```
1. Login
2. See today's tournee (if assigned and validated)
3. Tap START route
4. For each stop:
   a. Tap NAVIGATE -> opens Google Maps / Waze
   b. Tap ARRIVED when at location
   c. One of:
      DELIVERED -> POD -> submit -> next
      PARTIAL   -> quantities -> submit -> next
      FAILED    -> reason + comment -> submit -> next
5. All stops done -> tournee auto-closes
```

---

## 18. Environment Variables

API Gateway:
```
JWT_SECRET: asmsecret2026
DELIVERY_SERVICE_URL: http://delivery-service:8082
DRIVER_SERVICE_URL: http://driver-service:8086
APP_BACKEND_URL: http://app-backend:8080
```

App Backend:
```
JWT_SECRET: asmsecret2026
DB_URL: jdbc:postgresql://postgres-app:5432/app_db
DB_USER: app / DB_PASS: app
```

Delivery Service:
```
JWT_SECRET: asmsecret2026
INTERNAL_SECRET: asm-internal-2026
DB_URL: jdbc:postgresql://postgres-delivery:5432/delivery_db
DB_USER: delivery / DB_PASS: delivery
DRIVER_SERVICE_URL: http://driver-service:8086
ERP_TYPE: odoo
ODOO_URL: http://host.docker.internal:8069/jsonrpc
ODOO_DB: odoo / ODOO_UID: 2 / ODOO_PASSWORD: admin
MINIO_URL: http://minio:9000
MINIO_ACCESS_KEY: asmtracking
MINIO_SECRET_KEY: asmtracking2026
MINIO_BUCKET: pod-files
```

Driver Service:
```
JWT_SECRET: asmsecret2026
INTERNAL_SECRET: asm-internal-2026
DB_URL: jdbc:postgresql://postgres-driver:5432/driver_db
DB_USER: driver / DB_PASS: driver
```

Admin Dashboard:
```
NEXT_PUBLIC_API_URL: http://localhost:80
```

---

## 19. Docker Compose

```
api-gateway:       :80
app-backend:       :8080
delivery-service:  :8082
driver-service:    :8086
admin-dashboard:   :3000
postgres-app:      :5432
postgres-delivery: :5434
postgres-driver:   :5437
minio:             :9000 + :9001
odoo:              external :8069
```

Network: asm-network

---

## 20. Default Credentials

```
Dashboard: admin@asm-delivery.com / Admin@2026
MinIO:     asmtracking / asmtracking2026 (console :9001)
Odoo:      192.168.10.75:8069 / admin / admin
```

---


### Future V2
```
ERP Adapter as separate microservice :8085
DUX ERP full implementation
n8n (WhatsApp + SMS)
Flutter client app (read-only, ERP-linked)
Offline driver app
RMA / returns
Audit log
```

---

## 22. Build Order

```
1. Tournees backend (vehicles + routes + all endpoints)
2. Tournees in dashboard (/routes page)
3. Driver app redesign + route-based flow
4. Partial delivery (backend + driver app)
5. Cancellation sync to Odoo
6. Feuille de route PDF
7. RBAC in dashboard UI
8. Full end-to-end test + pre-prod deployment
```

---

## 23. Visual Identity

```
Brand: ASM Tracking
Logo: Package icon (lucide-react) + "ASM Tracking" wordmark

Colors:
  Primary:      #16A34A   Primary dark:  #15803D
  Light green:  #DCFCE7   White:         #FFFFFF
  Surface:      #F9FAFB   Border:        #E5E7EB
  Text:         #111827   Muted:         #6B7280

Status colors:
  WAITING_DRIVER: #F59E0B / #FEF3C7
  ASSIGNED:       #3B82F6 / #EFF6FF
  PICKED_UP:      #8B5CF6 / #F5F3FF
  IN_TRANSIT:     #F97316 / #FFF7ED
  DELIVERED:      #16A34A / #DCFCE7
  PARTIAL:        #F59E0B / #FEF3C7
  CANCELLED:      #6B7280 / #F9FAFB
  FAILED:         #EF4444 / #FEF2F2

Fonts:
  Body: Plus Jakarta Sans (400/500/600/700)
  Mono: JetBrains Mono (IDs, amounts, numbers, codes)

Rules:
  White backgrounds (light theme)
  Subtle shadows not hard borders
  8px border radius on cards
  shadcn/ui components
  lucide-react icons stroke 1.5
```

---

*End of context. Always read completely before any code changes.*
