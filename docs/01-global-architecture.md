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
        odoo["Odoo ERP x2"]
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
| **API Gateway** | Single entry point — routes requests, validates JWT, enforces CORS |
| **AppBackend** | Admin authentication (cookie JWT) and admin user management |
| **DeliveryMicroservice** | Core engine — orders, deliveries, routes, dispatch, SLA, outbox, WebSocket |
| **DriverService** | Driver authentication (Bearer JWT), profiles, FCM token storage, stats |
| **ErpAdapterService** | Multi-ERP adapter (Odoo / DUX) — translates delivery events into ERP calls with idempotency. Uses an embedded H2 database to record every processed `transactionId`. If OutboxProcessor retries the same event (e.g. HTTP timeout after Odoo already updated stock), ErpAdapterService detects the duplicate via H2 and skips the Odoo call — preventing a second stock decrement on the same delivery. |

---

## 1.3 Runtime Communication Flows

### Synchronous HTTP/REST

Toutes les requêtes clients passent par l'API Gateway (port 80) qui valide le JWT avant de router vers le bon service. Les appels entre services utilisent `X-Internal-Secret` et ne passent pas par la Gateway.

```mermaid
flowchart LR
    C1(Clients) -->|HTTPS| GW[API Gateway :80]
    GW -->|JWT| AB[AppBackend :8080]
    GW -->|JWT| DL[DeliveryMicroservice :8082]
    GW -->|JWT| DS[DriverService :8086]
    DL -->|X-Internal-Secret| DS
    DL -->|X-Internal-Secret| EA[ErpAdapterService :8088]
```

### Async Outbox

Les opérations critiques (sync ERP, stats livreur) sont écrites dans `outbox_event` dans la même transaction DB, puis traitées toutes les 20s avec SKIP LOCKED pour éviter les doublons en cas d'instances multiples.

```mermaid
flowchart LR
    OB[(outbox_event)] -->|20s SKIP LOCKED| OP[OutboxProcessor]
    OP -->|ERP sync| EA[ErpAdapterService]
    OP -->|stats increment| DS[DriverService]
    EA -->|JSON-RPC| OD[Odoo]
```

### Real-Time WebSocket STOMP

Le DeliveryMicroservice pousse les événements en temps réel via STOMP. L'admin voit les changements de statut et la position GPS du livreur instantanément, sans polling.

```mermaid
flowchart LR
    DL[DeliveryMicroservice] -->|deliveries events| ADM[Admin App]
    DL -->|routes events + driver.location_updated| ADM
    DL -->|driver events| DAPP[Driver App]
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
    DL["DeliveryMicroservice"] -->|"X-Internal-Secret + X-Company-Id"| EA["ErpAdapterService :8088"]

    EA --> CR["CompanyConfigResolver"]
    CR --> CAF["CompanyAdapterFactory"]

    CAF -->|company 1| OA1["OdooSyncAdapter 1"]
    CAF -->|company 2| OA2["OdooSyncAdapter 2"]
    CAF -->|DUX| DUX["DuxSyncAdapter STUB"]
    CAF -->|unconfigured| NOOP["NoopSyncAdapter"]

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

```mermaid
flowchart TD
    subgraph DM["DeliveryMicroservice"]
        EP["EventPublisher"]
        RWS["RouteWebSocketService"]
        SLA["SlaMonitor 30s"]
        EAN["ErpNotifier 120s"]
    end

    subgraph Topics["STOMP Topics"]
        T1["admin/companyId/deliveries"]
        T2["admin/companyId/routes"]
        T3["admin/companyId/erp"]
        T4["driver.driverId"]
    end

    EP --> T1
    EP --> T2
    EP --> T3
    RWS --> T4
    RWS --> T2
    SLA --> T1
    EAN --> T3

    T1 --> AdminApp["Admin App<br/>SockJS STOMP client"]
    T2 --> AdminApp
    T3 --> AdminApp
    T4 -->|SockJS STOMP| DriverApp["Driver App<br/>stomp_dart_client"]

    subgraph Fallback["Fallback"]
        POLL["15s polling<br/>when WebSocket unavailable"]
    end
    DriverApp -.->|on disconnect| POLL
```
