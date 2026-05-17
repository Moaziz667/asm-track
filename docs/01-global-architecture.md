# 01 — Global Architecture

## 1.1 System Context (C4 Level 1)

```mermaid
flowchart TB
    subgraph Users["Users"]
        admin(["Admin / Dispatcher"])
        driver(["Driver"])
        superadmin(["Super-Admin"])
    end

    subgraph ASM["ASM Track Platform"]
        core["ASM Track"]
    end

    subgraph External["External Systems"]
        odoo[" ERP "]
        fcm["Firebase FCM"]
        minio["MinIO"]
        osrm["OSRM"]
    end

    admin -->|HTTPS + WebSocket/STOMP| core
    driver -->|HTTPS + WebSocket/STOMP| core
    superadmin -->|HTTPS| core

    core -->|JSON-RPC 2.0 - stock sync, cancellations, backorders| odoo
    core -->|FCM SDK - delivery push notifications| fcm
    core -->|S3 API - POD upload / signed-URL| minio
    core -->|HTTP REST - route geometry + ETA| osrm
```

---

## 1.2 Container Diagram — Overview

![Container Diagram](img/container-diagram.png)

| Service | Role |
|---------|------|
| **API Gateway** | Single entry point — routes requests, validates JWT via JWKS, enforces CORS |
| **auth-server** | OAuth2 token issuer — RSA-signed JWTs for users and services. Exposes `/oauth2/token` (password + client_credentials grants) and `/oauth2/jwks` |
| **AppBackend** | Admin user management (companyId, role, multi-tenancy). Proxies login/refresh to auth-server |
| **DeliveryMicroservice** | Core engine — orders, deliveries, routes, dispatch, SLA, outbox, WebSocket |
| **DriverService** | Driver profiles, FCM token storage, stats. Proxies login/refresh to auth-server |
| **ErpAdapterService** | Multi-ERP adapter (Odoo / DUX) — translates delivery events into ERP calls with idempotency. Uses an embedded H2 database to record every processed `transactionId`. If OutboxProcessor retries the same event (e.g. HTTP timeout after Odoo already updated stock), ErpAdapterService detects the duplicate via H2 and skips the Odoo call — preventing a second stock decrement on the same delivery. |

---

## 1.3 Runtime Communication Flows

### Synchronous HTTP/REST

Toutes les requêtes clients passent par l'API Gateway (port 80) qui valide le JWT via la clé publique RSA (récupérée au démarrage depuis `auth-server/oauth2/jwks`). Les appels entre services utilisent des tokens OAuth2 client_credentials .

```mermaid
flowchart LR
    C1(Clients) -->|HTTPS| GW[API Gateway :80]
    GW -->|forward + validated JWT| AB[AppBackend :8080]
    GW -->|forward + validated JWT| DL[DeliveryMicroservice :8082]
    GW -->|forward + validated JWT| DS[DriverService :8086]
    AS[auth-server :8089] -->|JWKS public key at startup| GW
    AB -->|password grant| AS
    DS -->|password grant| AS
    DL -->|client credentials| AS
    DL -->|Bearer service token| DS
    DL -->|Bearer service token| EA[ErpAdapterService :8088]
    EA -->|client credentials| AS
```

### Async Outbox

Les opérations critiques (sync ERP) sont écrites dans `outbox_event` dans la même transaction DB, puis traitées toutes les 20s avec SKIP LOCKED pour éviter les doublons en cas d'instances multiples. Les events bloqués en `PROCESSING` (crash container) sont automatiquement remis en `PENDING` après 5 minutes.

```mermaid
flowchart LR
    OB[(outbox_event)] -->|20s SKIP LOCKED| OP[OutboxProcessor]
    OP -->|recover stuck > 5min| OB
    OP -->|ERP sync| EA[ErpAdapterService]
    EA -->|JSON-RPC| OD[Odoo]
```

### Real-Time WebSocket STOMP

Le DeliveryMicroservice pousse les événements en temps réel via STOMP, relayé par RabbitMQ. L'admin voit les changements de statut et la position GPS du livreur instantanément, sans polling.

```mermaid
flowchart LR
    DL[DeliveryMicroservice] -->|publish| RMQ[RabbitMQ STOMP relay]
    RMQ -->|deliveries events| ADM[Admin App]
    RMQ -->|routes events + driver.location_updated| ADM
    RMQ -->|driver events| DAPP[Driver App]
```

### Push Notifications FCM

Pour les notifications mobiles (nouvelle livraison assignée, arrêt ajouté, etc.), le DeliveryMicroservice récupère le token FCM du livreur depuis DriverService puis envoie via Firebase.

```mermaid
flowchart LR
    DL[DeliveryMicroservice] -->|get FCM token| DS[DriverService]
    DL -->|FCM SDK| FB[Firebase]
    FB -->|push| DAPP[Driver App]
```

### Binary Storage MinIO

Les photos de preuve de livraison (POD) sont envoyées en base64 au DeliveryMicroservice qui les stocke dans MinIO (compatible S3). Une URL signée est retournée pour affichage dans l'admin.

```mermaid
flowchart LR
    DAPP[Driver App] -->|POST photo base64| DL[DeliveryMicroservice]
    DL -->|S3 PUT| MN[MinIO]
    MN -->|Signed URL| DAPP
```

---

## 1.4 Multi-ERP Topology

```mermaid
flowchart TD
    DL["DeliveryMicroservice"] -->|"Bearer service token + X-Company-Id"| EA["ErpAdapterService :8088"]

    EA --> CR["CompanyConfigResolver"]
    CR --> CAF["CompanyAdapterFactory"]

    CAF -->|company 1| OA1["OdooSyncAdapter 1"]
    CAF -->|company 2| OA2["OdooSyncAdapter 2"]
    CAF -->|DUX| DUX["DuxSyncAdapter STUB"]
    OA1 -->|JSON-RPC| OD1["Odoo 1 :8069"]
    OA2 -->|JSON-RPC| OD2["Odoo 2 :8070"]

    subgraph Guard["Idempotency + In-flight Guard"]
        IFG["ConcurrentHashSet inFlight"]
        IDM["H2 idempotent_transaction"]
    end

    OA1 --> Guard
    OA2 --> Guard
```

---

## 1.5 WebSocket Topology

| Publisher | Topic | Subscriber | Événements |
|---|---|---|---|
| `EventPublisher` | `/topic/admin/{companyId}/deliveries` | Admin App | `delivery.scheduled` · `delivery.completed` · `delivery.failed` · `delivery.cancelled` · SLA breach |
| `EventPublisher` | `/topic/admin/{companyId}/routes` | Admin App | `route.validated` · `route.schedule_changed` · `stop_added` · `stop_removed` · `driver.location_updated` |
| `EventPublisher` | `/topic/admin/{companyId}/erp` | Admin App | `erp.synced` · `erp.failed` |
| `RouteWebSocketService` | `/topic/driver.{driverId}` | Driver App | `route.validated` · `STOP_ADDED` · `STOP_REMOVED` · `route.schedule_changed` |
| `SlaMonitoringService` *(60s)* | `/topic/admin/{companyId}/deliveries` | Admin App | alertes SLA dépassé |
| `ErpAutoImportNotifier` *(120s)* | `/topic/admin/{companyId}/erp` | Admin App | nouvelles commandes Odoo prêtes à importer |
