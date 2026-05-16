# 03 — Frontend Clients

## 3.1 Admin App

**Path:** `Apps/admin-app-snapshot-2026-04-07-171852`  
**Framework:** Next.js 16 (App Router) · Mantine UI · SockJS + @stomp/stompjs  
**State management:** Custom hooks + useState/axios (no React Query)

### Page Routes

| Route | Description |
|-------|-------------|
| `/` | Landing page (GSAP animations) |
| `/login` | Admin login |
| `/route-builder` | Route creation, Gantt timeline, stop management |
| `/deliveries` | Delivery list, dispatch, monitoring |
| `/depots` | Depot management |
| `/audit-logs` | Audit trail viewer |
| `/track/[deliveryId]` | Live single-delivery tracking |
| `/notifications` | Notification center |

### Authentication

- Login → `POST /api/auth/admin/login` `{email, password}`
- Response: JWT in response body + HTTP-only cookies (`access_token`, `refresh_token`)
- Client stores: `admin_role`, `admin_name`, `admin_user`, `admin_company_id` in localStorage
- Auto-refresh: On 401 → `POST /api/auth/admin/refresh` → if fails, clear + redirect `/login`
- All requests: `Authorization: Bearer {token}` header
- POST/PUT/PATCH: `Idempotency-Key: req-{timestamp}-{pathHash}` header added by interceptor

### API Calls

```
GET  /api/admin/routes                     → route list (filters: status, city, dateFrom, dateTo)
GET  /api/admin/deliveries                 → delivery list (paginated)
GET  /api/admin/deliveries/{id}            → delivery detail
GET  /api/admin/deliveries/{id}/geocode    → OpenStreetMap geocode
POST /api/admin/deliveries/{id}/pin-dropoff → set GPS coordinates
GET  /api/admin/ops/overview               → ops dashboard (period=day)
GET  /api/admin/ops/alerts                 → SLA alerts
GET  /api/admin/ops/lanes                  → lane performance
GET  /api/admin/routes/sla-summary         → route SLA aggregates
```

### WebSocket / Real-Time

- Library: `@stomp/stompjs` + `sockjs-client`
- Endpoint: `/ws` (SockJS transport)
- Auth: Bearer token in STOMP CONNECT headers

**Topics subscribed:**

| Topic | Events |
|-------|--------|
| `/topic/admin/{companyId}/deliveries` | delivery.created, scheduled, picked_up, in_transit, completed, failed, cancelled, reassigned, replanned, sla.breach |
| `/topic/admin/{companyId}/routes` | route.validated, schedule_changed, stop_added, stop_removed, delivery.handoff_confirmed, STOPS_TRANSFERRED_OUT/IN, **driver.location_updated** (real-time GPS pin on route map) |
| `/topic/admin/{companyId}/erp` | erp.sync_failed, erp.orders_ready |

**On event:**
- Toast notification shown
- Notification center store updated (localStorage, max 50 entries)
- Data invalidated (re-fetch triggers)

---

## 3.2 Super Admin App

**Path:** `Apps/asm-super-admin`  
**Framework:** Vite · React 19 · TanStack Router (file-based) · TanStack Query · Zustand (auth only)

### Page Routes

| Route | Description |
|-------|-------------|
| `/_authenticated/companies/` | Company CRUD + ERP config |
| `/_authenticated/drivers/` | Driver fleet management |
| `/_authenticated/users/` | Admin user management |
| `/_authenticated/vehicles/` | Vehicle fleet |
| `/_authenticated/routes/` | Route overview (read-only) |
| `/_authenticated/audit/` | Audit logs |
| `/_authenticated/settings/` | Account settings |

### Authentication

- Zustand `useAuthStore` holds session: `{accountNo, email, role[], exp, accessToken}`
- Token in HTTP-only cookie
- On 401: redirect to `/sign-in`
- Axios interceptor handles 401 redirect

### API Calls

**Companies:**
```
GET    /api/admin/companies              → list (filter: active/inactive)
POST   /api/admin/companies              → create {name, address, primaryColor, supportEmail, erpType, erpApiUrl, erpApiKey, erpDbName, erpUsername, erpUid}
PUT    /api/admin/companies/{id}         → update
DELETE /api/admin/companies/{id}         → deactivate
POST   /api/admin/companies/{id}/logo    → upload logo (multipart/form-data)
```

**Users:**
```
GET    /api/admin/users                  → list
POST   /api/admin/users                  → create {name, email, password, role, companyId}
PATCH  /api/admin/users/{id}/status      → toggle active/inactive
```

**Drivers:**
```
GET    /api/admin/drivers                → list
POST   /api/admin/drivers                → create
PUT    /api/admin/drivers/{id}           → update
PATCH  /api/admin/drivers/{id}/status    → toggle
POST   /api/admin/drivers/{id}/reset-password
GET    /api/admin/reports/drivers/{id}/performance/pdf  → PDF export
```

**Vehicles:**
```
GET    /api/admin/vehicles               → list
POST   /api/admin/vehicles               → create {make, model, manufactureYear, plate, type, payloadKg, fuelType}
PUT    /api/admin/vehicles/{id}          → update
DELETE /api/admin/vehicles/{id}          → delete
PATCH  /api/admin/vehicles/{id}/status   → AVAILABLE | IN_MAINTENANCE | OUT_OF_SERVICE
```

### React Query Keys

```
['companies'], ['admin-users'], ['admin-vehicles'], ['admin-drivers'], ['admin-routes']
```

---

## 3.3 Flutter Driver App

**Path:** `Apps/driverApp`  
**Framework:** Flutter · Riverpod (state) · Dio (HTTP) · Hive (offline queue) · stomp_dart_client  
**Build-time config:** `flutter build apk --dart-define=API_BASE_URL=https://...`

### Feature Structure

| Feature | Screens | Key Services |
|---------|---------|--------------|
| auth | login, register, splash | TokenStorage (FlutterSecureStorage), AuthRepository |
| deliveries | detail, card, history, handoff, scanner | DeliveryRepository, OfflineQueueService |
| routes | screen, calendar, detail, card | RouteRepository, RouteCacheService |
| pod | pod_form_screen | LocationService, DeliveryRepository |
| profile | screen, change-password | ProfileRepository |
| vehicle | inspection_screen | — (no backend endpoint) |
| home | home_shell | — |

### HTTP Client (api_client.dart)

- Base URL: `App_Config.fromEnvironment()` → `--dart-define=API_BASE_URL`
- Auth: `Authorization: Bearer {token}` on all requests except `/login`, `/register`, `/refresh-token`
- Token refresh: Auto on 401, Completer pattern prevents concurrent refreshes
- Timeouts: connect 15s, receive 25s, send 15s
- Idempotency-Key: `req-{timestamp}-{pathHash}` on POST/PUT/PATCH
- Correlation-ID: `X-Correlation-ID: trace-{timestamp}-{hash}` on all requests
- SSL pinning: **DISABLED** (code present, commented out — P2 priority)

### Token Storage

- Mechanism: `FlutterSecureStorage` (platform keychain/keystore)
- Keys: `access_token`, `refresh_token`, `token_type`, `expires_at`
- In-memory cache via `_cached` field

### All API Calls

**Auth:**
```
POST /api/auth/driver/login              {phone, password}
POST /api/auth/driver/register           {name, phone, password}
POST /api/auth/driver/refresh-token      {refreshToken}
```

**Deliveries:**
```
GET  /api/driver/deliveries/available                         → waiting deliveries in driver city
GET  /api/driver/deliveries/active                            → driver's active delivery (cached)
GET  /api/driver/deliveries/{id}                              → detail (cached)
POST /api/driver/deliveries/{id}/accept      idem: acc-{id}
POST /api/driver/deliveries/{id}/pickup      idem: pkp-{id}
POST /api/driver/deliveries/{id}/transit     {lat?, lng?}    idem: trns-{id}
POST /api/driver/deliveries/{id}/complete    idem: cmp-{id}  (deprecated, use /pod)
POST /api/driver/deliveries/{id}/fail        {failureCode, failureComment?}  idem: fail-{id}-{code}
POST /api/driver/deliveries/{id}/cancel      {reason?}       idem: can-{id}
POST /api/driver/deliveries/{id}/pod         {bonLivraisonPhotoBase64, packagePhotoBase64, comment?, lat?, lng?, isPartial, itemsDone?}  idem: pod-{id}
POST /api/driver/location                    {lat, lng}
POST /api/driver/deliveries/{id}/report      {reportType, description?}
POST /api/driver/deliveries/report-incident  {reportType, description, photosBase64[], deliveryId?, lat?, lng?}
PATCH /api/driver/deliveries/{id}/cod        {codCollected, codAmountCollected?}
GET  /api/driver/deliveries/{id}/handoff-token
POST /api/driver/deliveries/{id}/handoff     {token}
```

**Routes:**
```
GET  /api/driver/routes/today             → today's route (SharedPreferences cached)
GET  /api/driver/routes                   {from, to} query
POST /api/driver/routes/{routeId}/start   idem: start-route-{routeId}
POST /api/driver/routes/{routeId}/stops/{stopId}/arrive  idem: arrive-{routeId}-{stopId}
```

**Profile + FCM:**
```
GET  /api/driver/profile
GET  /api/driver/stats
PUT  /api/auth/driver/change-password     {oldPassword, newPassword, confirmPassword}
PUT  /api/driver/fcm-token                {fcmToken}
DELETE /api/driver/fcm-token
POST /api/driver/deliveries/location      {lat, lng, accuracy, timestamp}   (background)
```

### Offline Queue (Hive)

- Storage: `Box<Map>` named `'offline_queue'`
- Item structure: `{id, path, method, data, timestamp, idempotencyKey, retryCount}`
- Max retries: **3**
- TTL: **24 hours**
- 4xx response → remove immediately (permanent failure)
- 5xx response → increment retry, remove at max
- Network error → keep in queue, rely on TTL
- Replay trigger: On network connectivity restore
- Deduplication: If same idempotencyKey already queued → skip duplicate enqueue

### Idempotency Keys

| Operation | Key Format |
|-----------|-----------|
| Accept delivery | `acc-{deliveryId}` |
| Pickup | `pkp-{deliveryId}` |
| Transit | `trns-{deliveryId}` |
| Complete | `cmp-{deliveryId}` |
| Fail | `fail-{deliveryId}-{failureCode}` |
| Cancel | `can-{deliveryId}` |
| POD submit | `pod-{deliveryId}` |
| Start route | `start-route-{routeId}` |
| Arrive at stop | `arrive-{routeId}-{stopId}` |

### WebSocket (stomp_dart_client)

- URL: `{wsBaseUrl}/ws` (SockJS)
- Auth: `Authorization: Bearer {token}` in STOMP CONNECT headers
- Topic: `/topic/driver.{driverId}`
- Reconnect delay: 10 seconds
- Message model: `RouteWsEvent {event, routeId, routeName, clientName, erpOrderId, reason}`
- Fallback: 15-second polling when WebSocket unavailable

### GPS / Location

- Library: `geolocator`
- On-demand: `LocationAccuracy.best`
- Background tracking: `LocationAccuracy.high`, distance filter 50m, 30s interval
- Background service sends to: `POST /api/driver/deliveries/location`
- Geofence check: **DISABLED** for testing (pod_form_screen.dart lines 402-417)
  - When re-enabled: max distance 250m, accuracy threshold 100m

### POD Capture Flow

1. Driver opens `pod_form_screen`
2. Captures: bon de livraison photo (required) + package photo (required) + optional comment
3. Optionally attaches GPS coordinates (toggle switch)
4. If partial delivery: selects items and quantities delivered
5. Submit → `POST /api/driver/deliveries/{id}/pod` with base64-encoded photos
6. On network error → queued in Hive with key `pod-{deliveryId}`
7. Backend: saves photos to MinIO, updates delivery status to DELIVERED or PARTIALLY_DELIVERED

### Push Notifications (FCM)

- Firebase project: `driverapp-e7b37`
- Android channel: `asmtrack_high` (high importance, sound + vibration)
- Token lifecycle:
  - Registered on app launch → `PUT /api/driver/fcm-token`
  - Refreshed on `FirebaseMessaging.onTokenRefresh`
  - Deleted on logout → `DELETE /api/driver/fcm-token`
- Notification payload: `{title, body, type}` (type defaults to "GENERAL")

### Local Cache (SharedPreferences)

| Key | Contents | Fallback Trigger |
|-----|----------|-----------------|
| `cached_today_route` | Full DriverRoute JSON | Network error on route fetch |
| `cached_active_deliveries` | List<DriverDelivery> JSON | Network error |
| `cached_delivery_{id}` | DriverDelivery JSON | Network error |

Cache has no explicit TTL — overwritten on next successful fetch.
