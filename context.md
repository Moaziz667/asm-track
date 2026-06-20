# ASM Track — Codebase Context

> Last verified against source on 2026-06-14 by reading `Microservices/` and `Apps/admin-app-react/`.
> Supersedes earlier notes that described a custom AuthServer, Next.js admin, and multi-tenancy — none of which are true anymore.

Last-mile delivery tracking platform with ERP integration (Odoo live, DUX stubbed).
**Single-tenant**, real-time, offline-capable (driver app). Spring Boot backend + Vite/React admin SPA + Flutter driver app.

---

## Backend — Microservices/

All services: **Spring Boot 3.3.5, Java 17, Gradle**. Auth is **Keycloak** (OAuth2/OIDC) — there is no custom AuthServer.

| Service | Folder | DB | Role |
|---------|--------|----|------|
| API Gateway | `ApiGateway` | none | Spring Cloud Gateway reverse proxy; 123 route predicates; validates JWT as an OAuth2 **resource server** via Keycloak JWKS. Exposed on **:80** |
| App Backend | `AppBackend` | postgres-app (`app_db`) | Admin user/company CRUD; OAuth2 client + AMQP. 2 controllers |
| Delivery Microservice | `DeliveryMicroservice` | postgres-delivery (`delivery_db`) | **Core engine** (~29k LOC): orders, deliveries, routes, dispatch, SLA, analytics, ERP outbox, POD. 27 controllers |
| Driver Service | `DriverService` | postgres-driver (`driver_db`) | Driver profiles, FCM tokens, GPS, stats. 4 controllers |
| ERP Adapter | `ErpAdapterService` | H2 (idempotency only) | Multi-ERP adapter (Ports & Adapters); Odoo JSON-RPC; AMQP consumer. 4 controllers |

**Identity:** Keycloak 26.0.7, realm **`asm`**, exposed on **:8089** (→ container 8080), backed by its own `postgres-keycloak`. JWKS:
`http://keycloak:8080/realms/asm/protocol/openid-connect/certs`. Gateway and Delivery both validate against it.

**Infrastructure (docker-compose.yml):**
- **RabbitMQ 4** — :5672 (AMQP), :15672 (mgmt), **:61613 (STOMP)**. Acts as the STOMP **broker relay** for WebSocket fan-out.
- **MinIO** — :9000 / :9001. S3-compatible POD file storage (bucket `pod-files`).
- **OSRM** — :5000. Self-hosted routing (Tunisia OSM).
- **Postgres 16** × 4 (keycloak, app, delivery, driver).
- App-service ports (8080/8082/8086/8088) are **commented out** in compose — reachable only via the gateway.

### Delivery domain entities (`com/asm/delivery/entity/`)
Order, OrderItem, Delivery, DeliveryStatusHistory, Route, RouteStop, RouteAlert, RouteReport, DeliveryReport, ProofOfDelivery, Handoff, Rma/RmaItem, Vehicle, VehicleInspection, Depot, Zone, Company, Driver-refs (UUID), Notification, AuditLog, OutboxEvent, ProcessedRequest, SystemSetting, Tracking, FailureReason. Plus enums (DeliveryStatus, RouteStatus, RouteStopStatus, SlaStatus, FailureCode, OrderPriority/Source, HandoffState, VehicleType/Status, Role, ReportType, etc.).

### Status machines
- **Delivery:** UNSCHEDULED → SCHEDULED → PICKED_UP → IN_TRANSIT → DELIVERED / PARTIALLY_DELIVERED / FAILED / CANCELLED
- **Route:** DRAFT → VALIDATED → IN_PROGRESS → CLOSED / CANCELLED
- **RouteStop:** PENDING → SCHEDULED → PICKED_UP → IN_TRANSIT → ARRIVED → COMPLETED / FAILED / PARTIAL / FAILED_ATTEMPT / REMOVED_REPLANNED / REMOVED_CANCELLED

### Architectural patterns (verified in code)
1. **Transactional outbox** — `OutboxEvent` written in the same TX as the domain change; `OutboxProcessor` drains it → ERP sync without data loss.
2. **Idempotency** — `ErpAdapterService` caches successful Odoo responses (H2) by transaction id; retries return the cache → no double stock update.
3. **Optimistic locking** — `Delivery.version` guards concurrent dispatch.
4. **Ports & Adapters (ERP)** — `ErpSyncPort`/`ErpOrderPort`/`ErpLookupPort` + a per-company adapter factory (Odoo / DUX / Noop).
5. **Broker relay** — Delivery publishes → RabbitMQ STOMP → client subscriptions (decoupled, horizontally scalable).
6. **NOT multi-tenant.** No `TenantFilterAspect`, no `@FilterDef` across entities, no `X-Company-Id` query filter; `company_id` appears only on `FailureReason` as vestigial scaffolding. Treat data as single-tenant.

> Known hotspots (see review): god-services >930 LOC (`OpsAnalyticsService`, `DispatchService`, `DriverDeliveryService`, `EventPublisher`, `RoutePlanningService`, `ExceptionResolutionService`); `findAll().stream().filter()` whole-table loads; `RouteResponseMapper` queries repos (impure, N+1).

---

## Frontend — Apps/admin-app-react/

**Vite 8 + React 19 + TypeScript 6 + Tailwind v4 SPA** (this is the only admin app — the old Next.js `admin-app` and `asm-super-admin` are gone).

**Stack:**
- Routing: **react-router-dom 7** (routes defined in `src/App.tsx`, each wrapped in an `ErrorBoundary`).
- Data: **@tanstack/react-query 5** + **axios** (`src/lib/api.ts`); hooks in `src/hooks/` (16 files).
- State: **Zustand 5** stores (`lib/global-filters.ts`, `lib/global-map-store.ts`, `lib/i18n.ts`, `lib/modal-manager/`).
- Realtime: **@stomp/stompjs 7 + sockjs-client** via a single `components/RealtimeProvider.tsx`.
- Maps: **Leaflet + react-leaflet 5**. Charts: **recharts 3**. Drag-drop: **@dnd-kit**. Dates: **date-fns 4**.
- Tests: **Vitest 4** — pure-logic `.test.ts`, Node env, no jsdom/RTL, no `@/` alias (use relative imports).

**Auth:** OIDC **Authorization Code + PKCE** via **react-oidc-context / oidc-client-ts** against Keycloak realm `asm`, client **`admin-web`** (`src/lib/oidcConfig.ts`). Access token is mirrored to `localStorage` and attached as a `Bearer` header by the axios interceptor (`api` also sends `withCredentials`). Roles read from JWT `realm_access.roles`: **ADMIN, DISPATCHER, MANAGER** (backend also knows SUPER_ADMIN, DRIVER). `automaticSilentRenew` on; `/callback` + `/login` redirect URIs.

**Realtime topics** (`RealtimeProvider`): `/topic/admin.deliveries`, `/topic/admin.routes` (incl. driver GPS), `/topic/admin.erp`, `/topic/admin.security`. One socket; consumers subscribe by category via `useRealtimeEvent`.

**Structure:** `src/{pages (73 tsx), components (88 tsx), hooks, features, layouts, lib, locales, services, styles, types, ui}` + `keycloak-theme/` (custom Keycloak login theme).

**i18n:** FR/EN/AR via `useT()`/LocaleContext + `lib/{ux,en,ar}-copy.ts` (~3,400 lines each, kept in lockstep by hand — no parity check).

**Page decomposition pattern** (`dispatch-desk/`, and the Phase-4 splits `deliveries/`, `drivers/`, `dashboard/`, `route-details/`): page = thin orchestrator + `hooks/` (data) + `components/` + `constants.ts`/`types.ts`/`helpers.ts`. Target < 500 LOC/page. Remaining god-file: `route-builder/hooks/useRouteBuilder.ts` (~1,447 LOC).

> Build note: single `vendor` chunk ~2.3 MB (626 KB gzip) — Leaflet + recharts not yet route-split.

---

## Driver app — Apps/driverApp/ (Flutter)
Flutter; Riverpod + Dio + Hive offline queue; background GPS, FCM, STOMP, POD (photo+signature), QR handoff tokens, vehicle inspection. (Not re-audited in this pass — verify before relying on details.)

---

## Conventions
- Backend build/run via Docker (`Microservices/docker-compose.yml`); no `gradlew` invoked directly in the dev loop.
- Admin app: `npm run dev`, `npm test` (Vitest), `npx vite build`.
- Business timezone "today" = Africa/Tunis (UTC+1) — use `getBusinessDayKey`/`getDayBucket` from `lib/sla.ts`, not browser/UTC.
