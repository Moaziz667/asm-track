# 07 — Security Model

## 7.1 Authentication Strategies

The platform uses **OAuth 2.0** with a dedicated `auth-server` (Spring Boot) that issues RSA-signed JWTs. All services validate tokens via the JWKS public key endpoint — no shared secret.

### Admin login (cookie-based)

```mermaid
sequenceDiagram
    participant A as Admin Browser
    participant G as API Gateway
    participant AB as AppBackend
    participant AS as auth-server :8089
    participant DL as DeliveryMicroservice

    A->>G: POST /api/auth/admin/login {email, password}
    G->>AB: forward
    AB->>AB: verify account exists + active (postgres-app)
    AB->>AS: POST /oauth2/token {grant_type=password, email, password}
    AS->>AS: validate BCrypt password (postgres-app)
    AS-->>AB: {access_token, refresh_token} — RSA signed
    AB-->>A: Set-Cookie: access_token (httpOnly) + refresh_token (httpOnly)

    A->>G: GET /api/admin/routes (cookie sent automatically)
    G->>G: validate JWT via JWKS (RSA public key)
    G->>DL: forward + X-User-Id, X-User-Role, X-Company-Id headers
    DL-->>A: company-scoped response
```

### Driver login (Bearer token)

```mermaid
sequenceDiagram
    participant D as Driver App (Flutter)
    participant G as API Gateway
    participant DS as DriverService
    participant AS as auth-server :8089

    D->>G: POST /api/auth/driver/login {phone, password}
    G->>DS: forward
    DS->>DS: verify account exists + active (postgres-driver)
    DS->>AS: POST /oauth2/token {grant_type=password, phone, password}
    AS->>AS: validate BCrypt password (postgres-driver)
    AS-->>DS: {access_token, refresh_token} — RSA signed
    DS-->>D: {token, refreshToken, driver}
    D->>D: FlutterSecureStorage.write(access_token, refresh_token)
```

---

## 7.2 JWT Claims

All tokens are issued by `auth-server` and signed with **RSA-2048** (algorithm: RS256). Services validate using the public key from `GET /oauth2/jwks` — no shared secret.

### User access token (admin / driver)

```json
{
  "sub":       "uuid",
  "role":      "ADMIN | DISPATCHER | MANAGER | SUPER_ADMIN | DRIVER",
  "name":      "Ahmed Ben Ali",
  "type":      "access",
  "companyId": "uuid | null",
  "phone":     "+21698765432"
}
```

| Field | Admin | Driver |
|-------|-------|--------|
| `sub` | adminUser.id | driver.id |
| `role` | ADMIN / DISPATCHER / MANAGER / SUPER_ADMIN | DRIVER |
| `companyId` | uuid (null for SUPER_ADMIN) | absent |
| `phone` | absent | present |
| expiry | **1 hour** | **1 hour** |

### Service token (client_credentials)

```json
{
  "sub":  "delivery-service",
  "role": "SERVICE",
  "type": "service"
}
```

- Expiry: **5 minutes** — short-lived, auto-refreshed with caching
- Used for all internal service-to-service calls

### RSA Key Management

- Key pair generated from `AUTH_RSA_SEED` env var (deterministic — survives restarts)
- Public key exposed at `http://auth-server:8089/oauth2/jwks`
- All services fetch and cache the public key at startup
- **Key rotation:** change `AUTH_RSA_SEED` → all existing tokens immediately invalid → users re-login

---

## 7.3 Role-Based Access Control

### Roles

| Role | Who | Description |
|------|-----|-------------|
| `SUPER_ADMIN` | SaaS developer / platform owner | Cross-tenant access. Manages companies, creates admin accounts. No `companyId` in JWT — Hibernate tenant filter not applied. |
| `ADMIN` | Company admin | Full access scoped to their company. Manages drivers, vehicles, routes, deliveries, ERP config. |
| `DISPATCHER` | Operational dispatch staff | Creates and manages routes and deliveries. Cannot manage drivers/vehicles or company settings. |
| `MANAGER` | Operations manager | Read-only: stats, reports, ops dashboard. Cannot mutate deliveries or routes. |
| `DRIVER` | Delivery driver | Mobile app only. Executes deliveries: pickup, transit, POD, fail. No web admin access. |


### Endpoint Access Matrix

Enforced at two layers: **API Gateway** (`JwtGatewayFilter.isAuthorized`) and **DeliveryMicroservice** (`SecurityConfig`). Gateway is the outer enforcement; the microservice is a second check. SUPER_ADMIN bypasses all path rules at the Gateway level.

| Path Pattern | Allowed Roles | Enforced in |
|-------------|---------------|-------------|
| `/api/orders/**` | CLIENT | Gateway + DeliveryMS |
| `/api/deliveries/**` | DRIVER, DISPATCHER, ADMIN, SUPER_ADMIN | Gateway + DeliveryMS |
| `/api/driver/**` | DRIVER | Gateway + DeliveryMS |
| `/api/admin/companies/**` | SUPER_ADMIN | Gateway |
| `GET /api/admin/drivers/**` | ADMIN, DISPATCHER, SUPER_ADMIN | Gateway (read — assign to route) |
| `POST/PUT/PATCH /api/admin/drivers/**` | SUPER_ADMIN | Gateway (drivers are platform-owned) |
| `GET /api/admin/vehicles/**` | ADMIN, DISPATCHER, SUPER_ADMIN | Gateway (read — assign to route) |
| `POST/PUT/PATCH/DELETE /api/admin/vehicles/**` | SUPER_ADMIN | Gateway (vehicles are platform-owned) |
| `/api/admin/erp/**` | ADMIN, DISPATCHER, SUPER_ADMIN | Gateway (ERP order import is dispatcher work) |
| `/api/admin/reports/settings` | ADMIN, SUPER_ADMIN | Gateway (SLA config — not dispatcher) |
| `/api/admin/stats/**` | ADMIN, DISPATCHER, MANAGER, SUPER_ADMIN | Gateway + DeliveryMS |
| `/api/admin/reports/**` | ADMIN, DISPATCHER, MANAGER, SUPER_ADMIN | Gateway + DeliveryMS |
| `/api/admin/ops/**` | ADMIN, DISPATCHER, MANAGER, SUPER_ADMIN | Gateway + DeliveryMS |
| `/api/admin/**` | ADMIN, DISPATCHER, SUPER_ADMIN | Gateway + DeliveryMS |
| `/api/v1/**` | ADMIN, DISPATCHER, MANAGER, SUPER_ADMIN | Gateway + DeliveryMS |
| `/api/public/**` | PUBLIC (no auth) | Gateway |
| `/ws/**` | PUBLIC at HTTP level; auth via STOMP CONNECT frame | Gateway |
| `/internal/**` | Blocked at Gateway; validated by `InternalAuthFilter` inside services | Gateway |

---

## 7.4 Internal Service-to-Service Authentication

Services use **OAuth 2.0 Client Credentials** flow. Each service has a `client_id` + `client_secret` registered in `auth-server`. Before calling another service, it fetches a short-lived (5 min) service token and caches it.

```mermaid
sequenceDiagram
    participant DL as DeliveryMicroservice
    participant AS as auth-server
    participant DS as DriverService

    DL->>AS: POST /oauth2/token {grant_type=client_credentials, client_id, client_secret}
    AS-->>DL: {access_token, expires_in: 300}
    DL->>DS: PUT /internal/drivers/{id}/location
    Note over DL,DS: Authorization: Bearer {service_token}
    DS->>DS: validate JWT via JWKS (role=SERVICE)
    DS-->>DL: 200 OK
```

| Client ID | Secret Env Var | Calls |
|-----------|----------------|-------|
| `delivery-service` | `CLIENT_SECRET_DELIVERY` | DriverService, ErpAdapterService |
| `erp-adapter` | `CLIENT_SECRET_ERP` | DeliveryMicroservice (company config) |
| `app-backend` | `CLIENT_SECRET_APP` | DeliveryMicroservice (user deactivation) |
| `driver-service` | `CLIENT_SECRET_DRIVER` | (reserved) |

> **Replaces:** the previous `X-Internal-Secret` shared header approach.

---

## 7.5 Multi-Tenant Isolation (Data Layer)

### Hibernate Filter

All tenant-scoped entities are annotated with Hibernate `@Filter("companyFilter")`:

```java
@Filter(name = "companyFilter", condition = "company_id = :tenantId")
```

The filter is activated in the JwtAuthFilter via `TenantContext`:

```java
// JwtAuthFilter
String companyId = claims.get("companyId");
if (companyId != null) {
    TenantContext.set(UUID.fromString(companyId));
    session.enableFilter("companyFilter").setParameter("tenantId", companyId);
}
```

`TenantContext.clear()` is called in a `finally` block to prevent tenant bleed across requests.

### SUPER_ADMIN Bypass

When `companyId` is null (SUPER_ADMIN), the filter is not activated and all company records are visible.

---

## 7.6 Gateway Header Trust (Optional)

DeliveryMicroservice supports an optional mode where it trusts headers injected by the API Gateway
instead of re-validating the JWT itself:

```yaml
app.security.trust-gateway-headers: false  # DISABLED by default
app.security.gateway-secret: ${GATEWAY_SECRET:}
```

When enabled, the service accepts:
- `X-User-Id`, `X-User-Role`, `X-User-Name`, `X-Company-Id`, `X-Odoo-Partner-Id`
- Validated by `X-Gateway-Secret` header

This is disabled by default. Enable only if the API Gateway performs JWT validation and the
`GATEWAY_SECRET` is a strong shared secret.

---

## 7.7 Cookie Security (AppBackend)

```yaml
app.cookie.secure: ${COOKIE_SECURE:false}
```

| Setting | Value | When |
|---------|-------|------|
| `COOKIE_SECURE=false` | Default | Local development (HTTP) |
| `COOKIE_SECURE=true` | **Required in production** | HTTPS deployments |

The `SameSite=Strict` flag is always set. When `secure=true`, cookies are only sent over HTTPS.

---

## 7.8 SSL Pinning (Driver App)

SSL pinning code is present but **disabled** in `api_client.dart` lines 24-30:

```dart
// P2: SSL Pinning Infrastructure
// TODO: Enable before production release
// IOHttpClientAdapter().onHttpClientCreate = (client) {
//   SecurityContext context = SecurityContext();
//   context.setTrustedCertificatesBytes(certBytes);
//   return HttpClient(context: context);
// };
```

> **Production:** Re-enable SSL pinning and pin the production TLS certificate before public release.

---

## 7.9 Secrets Checklist for Production

| Secret | Env Var | Required Action |
|--------|---------|-----------------|
| RSA key seed | `AUTH_RSA_SEED` | Random 64-char string |
| Service secret — delivery | `CLIENT_SECRET_DELIVERY` | Strong random value |
| Service secret — driver | `CLIENT_SECRET_DRIVER` | Strong random value |
| Service secret — erp | `CLIENT_SECRET_ERP` | Strong random value |
| Service secret — app | `CLIENT_SECRET_APP` | Strong random value |
| Service secret — gateway | `CLIENT_SECRET_GW` | Strong random value |
| RabbitMQ user | `RABBITMQ_DEFAULT_USER` | Change from `guest` |
| RabbitMQ password | `RABBITMQ_DEFAULT_PASS` | Change from `guest` |
| MinIO access key | `MINIO_ACCESS_KEY` | Change from `asmtracking` |
| MinIO secret key | `MINIO_SECRET_KEY` | Change from `asmtracking2026` |
| Odoo password | `ODOO_PASSWORD` | Change from `admin` |
| Gateway shared secret | `GATEWAY_SECRET` | Set if trust-gateway-headers enabled |
| Cookie secure flag | `COOKIE_SECURE` | Set to `true` for HTTPS |
| Dead-letter webhook | `OUTBOX_ALERT_WEBHOOK_URL` | Set to Slack webhook URL |
| FCM service account | `FCM_SERVICE_ACCOUNT_PATH` | Mount actual Firebase JSON file |
