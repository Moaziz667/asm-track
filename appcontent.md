# ASM Track — Project Architecture & Conventions

> **Backend is the source of truth.** Frontend permissions must mirror backend RBAC rules exactly.

---

## 1. Project Overview

**ASM Track** is a delivery management platform (PFE) for a Tunisian logistics company. It provides:
- **Admin Web App** — route planning, dispatch desk, fleet management, ERP integration
- **Driver Mobile App** — delivery execution, real-time tracking, POD capture
- **Client Mobile App** — order placement, delivery tracking
- **ERP Integration** — Odoo sync for orders, warehouses, delivery notes

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENTS                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ Admin React  │  │ Driver App   │  │ Client App               │  │
│  │ (Vite+React) │  │ (Flutter)    │  │ (Flutter)                │  │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬─────────────┘  │
└─────────┼─────────────────┼───────────────────────┼────────────────┘
          │                 │                       │
          ▼                 ▼                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    API GATEWAY (port 80)                            │
│         Spring Cloud Gateway + JWT RBAC Filter                     │
│         Validates JWT, enforces roles, propagates headers          │
└──────┬──────────┬──────────┬──────────┬──────────┬─────────────────┘
       │          │          │          │          │
       ▼          ▼          ▼          ▼          ▼
  ┌─────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌─────────────┐
  │AppBackend│ │Delivery│ │Driver  │ │Auth    │ │ERP Adapter  │
  │  :8080  │ │ :8082  │ │ :8086  │ │ :8089  │ │   :8088     │
  └────┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └──────┬──────┘
       │          │          │          │              │
       ▼          ▼          ▼          ▼              ▼
  ┌─────────┐ ┌────────┐ ┌────────┐ ┌────────┐   ┌──────────┐
  │app_db   │ │delivery│ │driver  │ │app_db +│   │Odoo JSON │
  │:5435    │ │_db:5434│ │_db:5437│ │driver  │   │RPC (ext) │
  └─────────┘ └────────┘ └────────┘ └────────┘   └──────────┘

  Infrastructure:
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ RabbitMQ │  │  MinIO   │  │  OSRM    │
  │  :5672   │  │  :9000   │  │  :5000   │
  └──────────┘  └──────────┘  └──────────┘
```

---

## 3. Microservices

| Service | Port | DB | Responsibility |
|---------|------|----|----------------|
| **ApiGateway** | 80 | — | Route proxy, JWT validation, RBAC enforcement |
| **AuthServer** | 8089 | app_db + driver_db | OAuth2 token issuance (RSA-signed JWT) |
| **AppBackend** | 8080 | app_db | Admin users, auth delegation, ERP settings, client profiles |
| **DeliveryMicroservice** | 8082 | delivery_db | Core: deliveries, routes, orders, zones, vehicles, ops, audit |
| **DriverService** | 8086 | driver_db | Driver accounts, profiles, availability, FCM tokens |
| **ErpAdapterService** | 8088 | — | Odoo integration: lookup, sync, order resolution |

### Infrastructure

| Component | Port | Purpose |
|-----------|------|---------|
| **PostgreSQL (app)** | 5435 | Admin users, ERP settings, client accounts |
| **PostgreSQL (delivery)** | 5434 | Deliveries, routes, orders, zones, vehicles, depots |
| **PostgreSQL (driver)** | 5437 | Driver accounts, profiles, availability |
| **RabbitMQ** | 5672 / 15672 | Async messaging between services |
| **MinIO** | 9000 / 9001 | Object storage (POD files, logos) |
| **OSRM** | 5000 | Self-hosted routing engine (Tunisia OSM data) |

---

## 4. Tech Stack

### Backend
- **Java 17**, Spring Boot 3.3.5, Gradle
- Spring Security, Spring Data JPA, Spring WebSocket, Spring AMQP
- Flyway (migrations), PostgreSQL, Hypersistence Utils (JSONB)
- JJWT 0.12.3 (JWT), MinIO SDK, Firebase Admin SDK (FCM)
- PDF: OpenPDF + PDFBox + ZXing (QR) + JFreeChart
- Swagger: springdoc-openapi 2.6.0
- Lombok

### Frontend (Admin App)
- **React 19**, TypeScript 6, Vite 8
- React Router DOM 7, TanStack Query 5, Zustand 5
- Mantine 9, shadcn/ui, Tailwind CSS 4
- Leaflet (maps), Three.js (3D), Recharts (charts)
- @stomp/stompjs + SockJS (WebSocket), Axios
- DnD Kit (drag & drop), GSAP (animations)

### Mobile Apps
- **Flutter** (Driver App, Client App)

---

## 5. Authentication Flow

```
1. User logs in → AppBackend /api/auth/admin/login
   (or DriverService /api/auth/driver/login)
2. AppBackend delegates → AuthServer POST /oauth2/token (password grant)
3. AuthServer validates credentials against DB, issues RSA-signed JWT
4. JWT claims: sub (userId), role, name, type=access
5. All requests → ApiGateway validates JWT via JWKS
6. Gateway propagates X-User-Id, X-User-Role, X-User-Name headers
7. Each downstream service also validates JWT (defense in depth)
8. Service-to-service: client_credentials grant with pre-shared secrets
```

### Token Expiry
- Access token: 3600s (1 hour)
- Refresh token: 604800s (7 days)
- Service token: 300s (5 minutes)

### Service Clients
- `delivery-service`, `driver-service`, `erp-adapter`, `app-backend`, `api-gateway`

---

## 6. RBAC Rules (SOURCE OF TRUTH)

### Roles

| Role | Scope |
|------|-------|
| **ADMIN** | Unrestricted — full platform control |
| **DISPATCHER** | Operations: routes, deliveries, ERP, fleet (read-only on drivers/vehicles) |
| **MANAGER** | Oversight: stats, reports, ops (full); routes/deliveries (read-only GET) |
| **DRIVER** | Mobile app: delivery execution, status updates |
| **CLIENT** | Client app: order placement, tracking |
| **SERVICE** | Machine-to-machine (internal, not exposed via gateway) |

### Gateway Authorization Matrix (`JwtGatewayFilter.isAuthorized()`)

| Path Pattern | ADMIN | DISPATCHER | MANAGER | DRIVER | CLIENT |
|-------------|:-----:|:----------:|:-------:|:------:|:------:|
| `/api/admin/**` (catch-all) | Yes | Yes | — | — | — |
| `/api/admin/stats`, `/api/admin/reports/`, `/api/admin/ops/` | Yes | Yes | Yes | — | — |
| `/api/admin/routes` (GET), `/api/admin/deliveries` (GET) | Yes | Yes | Yes | — | — |
| `/api/admin/routes` (POST/PUT/DELETE) | Yes | Yes | — | — | — |
| `/api/admin/companies/me` | Yes | Yes | — | — | — |
| `/api/admin/companies/*` (other) | Yes | — | — | — | — |
| `/api/admin/drivers` (GET) | Yes | Yes | — | — | — |
| `/api/admin/drivers` (mutations) | Yes | — | — | — | — |
| `/api/admin/vehicles` (GET) | Yes | Yes | — | — | — |
| `/api/admin/vehicles` (mutations) | Yes | — | — | — | — |
| `/api/admin/erp/` | Yes | Yes | — | — | — |
| `/api/admin/reports/settings` | Yes | — | — | — | — |
| `/api/v1/**` (depots, zones) | Yes | Yes | Yes | — | — |
| `/api/driver/**` | — | — | — | Yes | — |
| `/api/deliveries/**` | Yes | Yes | — | Yes | — |
| `/api/orders/` | — | — | — | — | Yes |
| `/api/public/**` | Public | Public | Public | Public | Public |
| `/api/auth/**` | Public | Public | Public | Public | Public |
| `/ws/**` | Public (auth at STOMP CONNECT) | | | | |
| `/internal/**` | Blocked (403) | Blocked | Blocked | Blocked | Blocked |

---

## 7. API Gateway Route Map

| Route ID | Path | Target |
|----------|------|--------|
| `app-backend-admin-auth` | `/api/auth/admin/**` | AppBackend :8080 |
| `app-backend-dispatcher-auth` | `/api/auth/dispatcher/**` | AppBackend :8080 |
| `app-backend-client-auth` | `/api/auth/client/**` | AppBackend :8080 |
| `driver-auth` | `/api/auth/driver/**` | DriverService :8086 |
| `delivery-admin` | `/api/admin/deliveries/**` | Delivery :8082 |
| `delivery-admin-routes` | `/api/admin/routes/**` | Delivery :8082 |
| `delivery-admin-vehicles` | `/api/admin/vehicles/**` | Delivery :8082 |
| `delivery-admin-erp` | `/api/admin/erp/**` | Delivery :8082 |
| `delivery-admin-reports` | `/api/admin/reports/**` | Delivery :8082 |
| `delivery-admin-ops` | `/api/admin/ops/**` | Delivery :8082 |
| `delivery-admin-stats` | `/api/admin/stats/**` | Delivery :8082 |
| `delivery-admin-audit` | `/api/admin/audit/**` | Delivery :8082 |
| `delivery-admin-search` | `/api/admin/search` | Delivery :8082 |
| `delivery-admin-companies` | `/api/admin/companies/**` | Delivery :8082 |
| `delivery-admin-fleet` | `/api/admin/fleet/**` | Delivery :8082 |
| `driver-admin` | `/api/admin/drivers/**` | DriverService :8086 |
| `delivery-v1-depots` | `/api/v1/depots/**` | Delivery :8082 |
| `delivery-v1-zones` | `/api/v1/zones/**` | Delivery :8082 |
| `delivery-orders` | `/api/orders/**` | Delivery :8082 |
| `delivery-deliveries` | `/api/deliveries/**` | Delivery :8082 |
| `delivery-driver-deliveries` | `/api/driver/deliveries/**` | Delivery :8082 |
| `delivery-driver-routes` | `/api/driver/routes/**` | Delivery :8082 |
| `driver-service` | `/api/driver/**` | DriverService :8086 |
| `app-backend-profile` | `/api/profile/**` | AppBackend :8080 |
| `app-backend-settings` | `/api/settings/**` | AppBackend :8080 |
| `app-backend-admin-clients` | `/api/admin/clients/**` | AppBackend :8080 |
| `app-backend-admin-users` | `/api/admin/users/**` | AppBackend :8080 |
| `delivery-ws` | `/ws/**` | Delivery :8082 |
| `delivery-public-tracking` | `/api/public/**` | Delivery :8082 |
| `internal-app-backend` | `/internal/users/**`, `/internal/settings/**` | AppBackend :8080 |
| `internal-app-backend-admin-users` | `/internal/admin-users/**` | AppBackend :8080 |
| `internal-driver-service` | `/internal/drivers/**` | DriverService :8086 |
| `odoo-products` | `/api/products/**` | Odoo :3100 |

---

## 8. Frontend Architecture (admin-app-react)

### Router
- **React Router DOM v7** with `createBrowserRouter` in `src/App.tsx`
- Three route groups:
  - **Public** (PublicRoute guard): `/login`
  - **Public tracking** (no guard): `/track/:deliveryId`
  - **Protected** (ProtectedRoute + AppShell layout): all authenticated pages
- Lazy-loaded heavy pages: DispatchDesk, RouteBuilder, RouteDetails, Zones
- Catch-all `*` → redirect to `/dashboard`

### Route Guards
- `ProtectedRoute`: checks `access_token` or user data in localStorage → redirect to `/login`
- `PublicRoute`: if authenticated → redirect to `/dashboard`
- `useRoleGuard(checkFn)`: per-page hook, redirects to `/dashboard` if role fails check

### Permission Functions (`src/lib/auth.ts`)

| Function | Allowed Roles | Used By |
|----------|--------------|---------|
| `canManageSettings` | ADMIN | Settings, ERP Integration |
| `canDispatch` | ADMIN, DISPATCHER | Deliveries, Dispatch, Fleet, Audit |
| `canManageRoutes` | ADMIN, DISPATCHER | Route Builder |
| `canImportErp` | ADMIN, DISPATCHER | Import page |
| `canManageMasterData` | ADMIN, DISPATCHER | (available but not used in guards) |
| `canViewReadOnly` | ADMIN, DISPATCHER, MANAGER | Routes Table, Operations (sidebar) |

### Pages with Inline Role Guards

| Page | Guard Function | Redirect |
|------|---------------|----------|
| `DeliveriesPage` | `canDispatch` | `/dashboard` |
| `OperationsPage` | `canDispatch` | `/dashboard` |
| `RoutesTablePage` | `canDispatch` | `/dashboard` |
| `RouteBuilderPage` (via hook) | `canManageRoutes` | `/dashboard` |
| `AuditLogsPage` | `canDispatch` | `/dashboard` |
| `SettingsPage` | `canManageSettings` (UI toggle) | — |
| `ErpIntegrationPage` | `canManageSettings` (UI toggle) | — |

### Sidebar Navigation (`src/components/Sidebar.tsx`)

| Group | Item | Path | roleCheck |
|-------|------|------|-----------|
| Operations | Dashboard | `/dashboard` | (none — all) |
| Operations | Overview | `/operations` | `canViewReadOnly` |
| Operations | Dispatch | `/dispatch-desk` | `canDispatch` |
| Deliveries | Tracking | `/deliveries` | `canDispatch` |
| Deliveries | Import | `/import` | `canImportErp` |
| Planning | Create Route | `/route-builder` | `canManageRoutes` |
| Planning | Routes Table | `/routes-table` | `canViewReadOnly` |
| Fleet | Drivers | `/drivers` | `canDispatch` |
| Fleet | Vehicles | `/vehicles` | `canDispatch` |
| Fleet | Depots | `/depots` | `canDispatch` |
| Fleet | Zones | `/zones` | `canDispatch` |
| Analytics | Performance | `/performance` | (none — all) |
| Analytics | Audit | `/audit-logs` | `canDispatch` |
| Settings | General | `/settings` | `canManageSettings` |
| Settings | ERP | `/settings/erp` | `canManageSettings` |

### API Client (`src/lib/api.ts`)
- Axios instance with base URL `/api`
- Request interceptor: auto-generates `X-Idempotency-Key` for mutations
- Response interceptor: on 401 → silent token refresh via `POST /api/auth/admin/refresh` → retry queued requests → on failure → clear auth + redirect to `/login`

### Auth Storage (localStorage keys)
- `access_token` — JWT access token
- `admin_user` — JSON user object
- `admin_name` — display name
- `admin_role` / `token_role` / `role` / `user_role` — role (checked in order)

---

## 9. Domain Enums

### DeliveryStatus
`UNSCHEDULED`, `SCHEDULED`, `PICKED_UP`, `IN_TRANSIT`, `DELIVERED`, `PARTIALLY_DELIVERED`, `FAILED`, `CANCELLED`

### RouteStatus
`DRAFT`, `VALIDATED`, `IN_PROGRESS`, `CLOSED`, `CANCELLED`

### RouteStopStatus
`PENDING`, `SCHEDULED`, `PICKED_UP`, `IN_TRANSIT`, `ARRIVED`, `COMPLETED`, `FAILED`, `PARTIAL`, `FAILED_ATTEMPT`, `REMOVED_REPLANNED`, `REMOVED_CANCELLED`

### Role
`CLIENT`, `DRIVER`, `DISPATCHER`, `MANAGER`, `ADMIN`, `SYSTEM`

### VehicleStatus
`AVAILABLE`, `IN_MAINTENANCE`, `OUT_OF_SERVICE`

### DriverAccountStatus
`PENDING_SETUP`, `ACTIVE`, `SUSPENDED`

### DriverOnlineStatus
`OFFLINE`, `ONLINE`, `ON_BREAK`

### OrderSource
`APP`, `ODOO`

### OrderStatus
`PENDING`, `DELIVERED`, `PARTIALLY_DELIVERED`, `CANCELLED`

### SlaStatus
`ON_TIME`, `EARLY`, `LATE`

### HandoffState
`REQUESTED`, `IN_PROGRESS`, `CONFIRMED`, `EXPIRED`, `CANCELLED`

---

## 10. Code Conventions

### Backend
- **Package structure**: `com.asm.<service>.{config, controller, dto, entity, repository, security, service}`
- **DTOs**: `dto/request/` and `dto/response/` sub-packages
- **Entities**: JPA entities with Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`)
- **Migrations**: Flyway (`db/migration/V1__*.sql`)
- **Security**: Each service has its own `SecurityConfig` + `JwtAuthFilter` (defense in depth)
- **Internal endpoints**: `/internal/**` for service-to-service, protected by SERVICE role JWT
- **Audit**: `@PrePersist` / `@PreUpdate` for `createdAt` / `updatedAt` timestamps
- **Timezone**: `Africa/Tunis` (set in Docker environment)

### Frontend
- **Path alias**: `@/` → `src/`
- **Components**: PascalCase, co-located with `.module.scss` styles
- **Pages**: `src/pages/` — one file per page, sub-folders for complex pages
- **Hooks**: `src/hooks/` for shared hooks, co-located for page-specific
- **Services**: `src/services/` — API call wrappers
- **Types**: `src/types/` — shared TypeScript interfaces
- **i18n**: Custom locale system (`src/lib/LocaleContext.tsx`, `src/lib/i18n.ts`) — supports FR, AR, EN
- **Styling**: Tailwind CSS 4 + SCSS modules + Mantine components
- **State**: TanStack Query for server state, Zustand for client state, React context for UI state

---

## 11. Known Bugs & Misalignments

### BUG 1 — MANAGER blocked from Operations page
- **Frontend**: `OperationsPage.tsx` uses `canDispatch(role)` → ADMIN/DISPATCHER only
- **Backend**: `/api/admin/ops/**` allows ADMIN + DISPATCHER + **MANAGER**
- **Fix**: Guard should use `canViewReadOnly` (allows MANAGER read-only access)

### BUG 2 — MANAGER blocked from Routes Table
- **Frontend**: `RoutesTablePage.tsx` uses `canDispatch(role)` → ADMIN/DISPATCHER only
- **Backend**: `GET /api/admin/routes` allows ADMIN + DISPATCHER + **MANAGER**
- **Fix**: Guard should use `canViewReadOnly`

### BUG 3 — MANAGER blocked from Deliveries page
- **Frontend**: `DeliveriesPage.tsx` uses `canDispatch(role)` → ADMIN/DISPATCHER only
- **Backend**: `GET /api/admin/deliveries` allows ADMIN + DISPATCHER + **MANAGER**
- **Fix**: Guard should use `canViewReadOnly`

### BUG 4 — Sidebar hides Deliveries from MANAGER
- **Frontend**: `Sidebar.tsx` tracking item uses `canDispatch` → hidden from MANAGER
- **Backend**: MANAGER can GET `/api/admin/deliveries`
- **Fix**: Use `canViewReadOnly` instead

### BUG 5 — Companies page visible to MANAGER but backend blocks them
- **Frontend**: Companies sidebar item has **no roleCheck** → visible to all roles
- **Backend**: `/api/admin/companies/me` → ADMIN + DISPATCHER only
- **Fix**: Add `roleCheck: canDispatch` to the Companies sidebar item

### BUG 6 — Sidebar telemetry fails for MANAGER
- **Frontend**: Sidebar calls `GET /api/admin/erp/pending-orders` for all users every 30s
- **Backend**: `/api/admin/erp/` → ADMIN + DISPATCHER only
- **Fix**: Skip ERP telemetry call for MANAGER role

### BUG 7 — Backend SecurityConfig inconsistency
- **Gateway**: `/api/admin/companies/me` → ADMIN + DISPATCHER (no MANAGER)
- **DeliveryMicroservice SecurityConfig**: same path → ADMIN + DISPATCHER + MANAGER
- **Impact**: Gateway blocks MANAGER before it reaches the service (effectively correct)
- **Fix**: Remove MANAGER from DeliveryMicroservice SecurityConfig for `/api/admin/companies/me`

---

## 12. Running the Project

### Backend (Docker Compose)
```bash
cd Microservices
docker compose up --build
```
Gateway exposed on port 80. All other services are internal.

### Frontend (Admin App)
```bash
cd Apps/admin-app-react
npm install
npm run dev        # dev server with Vite proxy to localhost:80
npm run build      # production build (tsc + vite)
npm run lint       # ESLint
```

### Driver App (Flutter)
```bash
cd Apps/driverApp
flutter pub get
flutter run
```

---

## 13. Environment Variables

Key variables in `Microservices/.env`:
- `APP_DB_PASS`, `DELIVERY_DB_PASS`, `DRIVER_DB_PASS` — database passwords
- `AUTH_RSA_SEED` — deterministic RSA key generation seed
- `CLIENT_SECRET_*` — service-to-service OAuth2 secrets
- `GATEWAY_SECRET` — gateway header trust secret
- `RABBITMQ_USER`, `RABBITMQ_PASS` — message broker credentials
- `MINIO_USER`, `MINIO_PASS` — object storage credentials
- `ODOO_URL`, `ODOO_DB`, `ODOO_UID`, `ODOO_PASSWORD` — ERP connection
- `AES_ENCRYPTION_KEY` — ERP settings encryption
- `RESEND_API_KEY` — email service (driver invites)
