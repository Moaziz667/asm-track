# Backend Team Documentation

## 1. Purpose and Audience
This document is the operational and technical reference for backend engineers working on:
- Delivery Service (`Microservices/DeliveryMicroservice`)
- App Backend Service (`Microservices/AppBackend`)

It covers architecture, responsibilities, API domains, data model, security, and workflow behavior used by Admin, Driver, and Client experiences.

## 2. High-Level Architecture

### 2.1 Service Responsibilities
| Service | Port | Main Responsibility |
|---|---:|---|
| App Backend | 8080 | Identity/profile backend and admin user management |
| Delivery Service | 8082 | Delivery lifecycle, routes, dispatch ops, tracking, POD, ERP-linked import/actions |
| Driver Service (external dependency) | 8086 (default URL) | Driver profiles, availability, location, stats (called by Delivery Service) |
| Odoo (external dependency) | 8069 JSON-RPC | ERP source of truth for clients/orders/stock moves |
| MinIO | 9000 | POD media storage |
| PostgreSQL (per service) | 5433/5434 default env mapping | Persistence per bounded context |

### 2.2 Runtime Data Flow
1. Admin authenticates via App Backend (`/api/auth/admin/login`) and receives JWT.
2. Admin UI calls Delivery Service for deliveries, routes, KPIs, and exceptions.
3. Driver app authenticates as DRIVER and executes delivery + route workflow in Delivery Service.
4. Delivery Service synchronizes with:
- Driver Service: availability/location/stats.
- Odoo: pending order import and stock/status sync on final outcomes.
- MinIO: proof of delivery media.

## 3. Security and Roles

### 3.1 Delivery Service (`com.asm.delivery.config.SecurityConfig`)
Role routing summary:
- Public: `/api/auth/driver/**`, `/api/dev/**`, Swagger endpoints.
- CLIENT: `/api/orders/**`.
- DRIVER: `/api/driver/**` and driver-specific endpoints.
- ADMIN / DISPATCHER / MANAGER: `/api/admin/ops/**`, `/api/admin/reports/**`.
- ADMIN / DISPATCHER: other `/api/admin/**` domains.
- Mixed: `/api/deliveries/**` for CLIENT/DRIVER/DISPATCHER/ADMIN.

### 3.2 App Backend (`com.asm.appbackend.config.SecurityConfig`)
Role routing summary:
- Public: `/api/auth/**`, Swagger endpoints.
- CLIENT: `/api/profile/**`.
- ADMIN: `/api/admin/users`, `/api/admin/clients/**`.

### 3.3 JWT
- Both services use HMAC JWT (`app.jwt.secret`) with role claim.
- App Backend currently issues admin/client tokens from `JwtService`.
- Delivery Service validates JWT through its own JWT filter and role-based path guards.

## 4. App Backend Service Details

### 4.1 Functional Scope
Current practical scope:
- Admin authentication (`/api/auth/admin/login`).
- Admin user management (`/api/admin/users`).
- Client profile endpoints (`/api/profile/**`).

ERP-aligned restrictions currently enforced:
- Client auth endpoints under `/api/auth/client/**` are intentionally blocked.
- Admin clients listing endpoint currently blocked and delegated to ERP governance.

### 4.2 Main API Domains
| Domain | Controller | Notes |
|---|---|---|
| Admin auth | `AdminAuthController` | JWT issuance for admin dashboards |
| Admin users | `AdminUserController` | Create/list internal admin users |
| Client auth (disabled) | `AuthController` | Returns forbidden due to ERP-first client management |
| Profile | `ProfileController` | Client profile and password operations |
| Admin clients (disabled) | `AdminClientController` | Returns forbidden due to ERP ownership |

### 4.3 Persistence (App Backend)
Core tables in schema:
- `clients`
- `client_otp`
- `admin_users`

Note: schema retains client entities, but business policy now prioritizes ERP for client identity onboarding and listing.

## 5. Delivery Service Details

### 5.1 Functional Scope
- Order intake and lifecycle support (legacy app-side + ERP import).
- Delivery state machine for driver execution.
- Dispatch and route planning.
- Ops dashboards (SLA, lanes, exceptions, audit, alerts).
- POD capture/read.
- Backorder and partial delivery handling.

### 5.2 Main API Domains
| Domain | Base Path | Key Controller |
|---|---|---|
| Admin deliveries | `/api/admin/deliveries` | `AdminDeliveryController` |
| Admin ops board | `/api/admin/ops` | `AdminOpsController` |
| Admin routes | `/api/admin/routes` | `AdminRouteController` |
| Admin vehicles | `/api/admin/vehicles` | `AdminVehicleController` |
| Admin reports | `/api/admin/reports` | `AdminReportsController` |
| Admin ERP lookups/import | `/api/admin/erp` | `ErpController` |
| Driver deliveries | `/api/driver/deliveries` | `DriverDeliveryController` |
| Driver routes | `/api/driver/routes` | `DriverRouteController` |
| Cross-role delivery read APIs | `/api/deliveries` | `DeliveryController` |
| Legacy client orders | `/api/orders` | `OrderController` |
| Dev utilities | `/api/dev` | `DevController` |

### 5.3 Delivery State Model
Primary statuses in schema and service logic:
- `WAITING_DRIVER`
- `ASSIGNED`
- `PICKED_UP`
- `IN_TRANSIT`
- `DELIVERED`
- `PARTIALLY_DELIVERED`
- `FAILED`
- `CANCELLED`

History is persisted in `delivery_status_history` with actor role constraints:
- `CLIENT`, `DRIVER`, `DISPATCHER`, `MANAGER`, `ADMIN`, `SYSTEM`.

### 5.4 Route Model
Route lifecycle (high level):
- `DRAFT` -> `VALIDATED` -> `IN_PROGRESS` -> `CLOSED`

Stops are managed with ordering and stop statuses in route stop entities. Driver route endpoints expose today route and stop arrival operations.

### 5.5 Event Publication
`EventPublisher` currently logs event records and is the integration seam for future webhook/bus delivery.
Recent events include:
- `delivery.reassigned`
- `delivery.replanned`
- plus existing created/assigned/picked_up/in_transit/completed/failed/cancelled events.

## 6. Canonical Workflows

### 6.1 Admin Route-First Assignment Workflow
1. Admin imports or creates delivery candidates.
2. Admin creates route (`/api/admin/routes`) and adds stops.
3. Admin validates route.
4. Driver receives route in `/api/driver/routes/today`.
5. Driver starts route and executes stop arrival + delivery status transitions.

### 6.2 Driver Execution Workflow
1. Driver accepts delivery (if in waiting lane) or follows assigned route stops.
2. Driver picks up package.
3. Driver marks transit.
4. Driver completes via POD, or fails/cancels with reason.
5. Delivery history and tracking are persisted.
6. Transport adapter updates availability/stats best-effort.
7. Odoo sync triggers on completion/failure business outcomes where configured.

### 6.3 Exceptions Workflow (Ops Board)
Main feed endpoint:
- `GET /api/admin/ops/exceptions`

Quick actions:
- `POST /api/admin/ops/exceptions/{deliveryId}/reassign`
- `POST /api/admin/ops/exceptions/{deliveryId}/replan`

Rules currently enforced in service logic:
- Reassign allowed for `ASSIGNED`, `PICKED_UP`.
- Replan allowed for `ASSIGNED`, `PICKED_UP`, `FAILED`.
- Picked-up reassignment requires note (handover traceability).
- Reassign updates route stop ownership to target driver's route.
- Replan removes delivery from execution route and returns to waiting lane.
- Events emitted for reassign/replan.

### 6.4 Odoo Boundary Workflow
1. ERP data is searched/imported via `/api/admin/erp/*` endpoints.
2. Delivery execution progresses in Delivery Service.
3. Final operational outcomes trigger ERP sync actions.
4. Dispatch actions (`reassign`, `replan`) stay operational and do not directly mutate ERP stock state.

## 7. Data Model Summary

### 7.1 Delivery Service (selected tables)
- `orders`
- `deliveries`
- `delivery_status_history`
- `tracking`
- `delivery_reports`
- route/vehicle-related tables (route domain)
- POD and media metadata tables (with MinIO object storage)

### 7.2 App Backend (selected tables)
- `clients`
- `client_otp`
- `admin_users`

## 8. Endpoint Catalog (Quick Reference)

### 8.1 App Backend
- `POST /api/auth/admin/login`
- `POST /api/admin/users`
- `GET /api/admin/users`
- `GET /api/profile`
- `PUT /api/profile`
- `PUT /api/profile/password`
- `POST /api/auth/client/register` (disabled)
- `POST /api/auth/client/verify-otp` (disabled)
- `POST /api/auth/client/resend-otp` (disabled)
- `POST /api/auth/client/login` (disabled)
- `GET /api/admin/clients` (disabled)

### 8.2 Delivery Service (high-use)
Admin:
- `GET /api/admin/deliveries`
- `GET /api/admin/deliveries/{id}`
- `POST /api/admin/deliveries/{id}/assign`
- `POST /api/admin/deliveries/{id}/cancel`
- `GET /api/admin/deliveries/stats`
- `GET /api/admin/ops/overview`
- `GET /api/admin/ops/exceptions`
- `POST /api/admin/ops/exceptions/{deliveryId}/reassign`
- `POST /api/admin/ops/exceptions/{deliveryId}/replan`
- `GET /api/admin/routes`
- `POST /api/admin/routes`
- `PUT /api/admin/routes/{id}/validate`

Driver:
- `GET /api/driver/deliveries/available`
- `GET /api/driver/deliveries/active`
- `POST /api/driver/deliveries/{id}/accept`
- `POST /api/driver/deliveries/{id}/pickup`
- `POST /api/driver/deliveries/{id}/transit`
- `POST /api/driver/deliveries/{id}/pod`
- `POST /api/driver/deliveries/{id}/fail`
- `POST /api/driver/deliveries/{id}/cancel`
- `GET /api/driver/routes/today`
- `POST /api/driver/routes/{id}/start`

Shared read APIs:
- `GET /api/deliveries/{id}`
- `GET /api/deliveries/{id}/tracking`
- `GET /api/deliveries/{id}/history`
- `GET /api/deliveries/{id}/pod`

## 9. Operational Notes for Team
1. Keep route state and delivery state consistent; dispatch actions must update both where applicable.
2. Preserve audit trail on all ops actions, especially custody-changing operations.
3. Use idempotent annotations/keys for retry-prone admin actions.
4. Maintain ERP boundary discipline: avoid accidental stock-impacting calls from pure dispatch actions.
5. Treat transport adapter calls as best-effort and non-blocking where business-critical path allows.

## 10. Known Limitations and Next Enhancements
1. EventPublisher currently logs events; replace with webhook/message bus integration for true push notifications.
2. Driver app currently relies on polling-based awareness for assignment changes unless push is added.
3. Legacy client auth/order endpoints remain for compatibility but strategy is ERP-first client/order management.
4. Consider adding contract tests between gateway and both services for role-path policy regressions.

## 11. Suggested Team Workflow for Changes
1. Update service logic and controller contracts.
2. Update this document section(s): endpoints + workflow + role policy.
3. Run smoke tests for impacted workflows.
4. Verify dashboard/driver UI behavior for state transitions.
5. Capture release notes with migration notes if schema constraints changed.

---
Last updated: 2026-04-03

