# 11 — Database Schema

> Dumped live from running containers — PostgreSQL 16.13 — `pg_dump --schema-only`

---

## 11.1 `delivery_db` (DeliveryMicroservice)

Main operational database. All tables are multi-tenant via `company_id`.

### Tables

#### `orders`
Central order record, one per customer shipment.

```sql
CREATE TABLE public.orders (
    id                     uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    source                 varchar(10)  NOT NULL,          -- APP | ODOO
    schema_version         varchar(10)  DEFAULT '1.0.0',
    company_id             uuid         NOT NULL REFERENCES companies(id),
    client_id              varchar(100),
    client_name            varchar(100) NOT NULL,
    client_phone           varchar(20),
    client_email           varchar(100),
    erp_order_id           varchar(100),
    erp_external_ref       varchar(100),
    -- Origin (warehouse / depot)
    origin_name            varchar(100),
    origin_address         text,
    origin_city            varchar(100),
    origin_postal_code     varchar(20),
    origin_country_code    varchar(2),
    origin_contact_name    varchar(100),
    origin_contact_phone   varchar(20),
    origin_contact_email   varchar(100),
    -- Dropoff (customer)
    dropoff_address        text         NOT NULL,
    dropoff_city           varchar(100),
    dropoff_postal_code    varchar(20),
    dropoff_country_code   varchar(2)   DEFAULT 'TN',
    dropoff_lat            numeric(10,7),
    dropoff_lng            numeric(10,7),
    delivery_instructions  text,
    -- Financial
    total_amount           numeric(10,3) NOT NULL,
    currency               varchar(3)   DEFAULT 'TND',
    payment_type           varchar(10),                    -- COD | PREPAID
    amount_to_collect      numeric(10,3) DEFAULT 0,
    is_cod                 boolean      DEFAULT false,
    -- Scheduling
    scheduled_at           timestamp,
    priority               varchar(10)  DEFAULT 'NORMAL',  -- NORMAL | HIGH
    -- Items (JSONB array)
    items                  jsonb        NOT NULL,
    total_quantity         integer      DEFAULT 0,
    total_weight_kg        numeric(10,3) DEFAULT 0,
    -- ERP sync
    odoo_sync_status       varchar(40)  DEFAULT 'SYNCED',
    odoo_backorder_id      integer,
    parent_order_id        uuid REFERENCES orders(id),
    sync_retry_count       integer      DEFAULT 0,
    next_sync_retry_at     timestamp,
    -- Status
    status                 varchar(20)  DEFAULT 'PENDING', -- PENDING | DELIVERED | PARTIALLY_DELIVERED | CANCELLED
    last_synced_at         timestamp,
    zone_id                uuid REFERENCES zones(id),
    created_at             timestamp    DEFAULT now(),
    updated_at             timestamp    DEFAULT now(),
    UNIQUE (erp_order_id, company_id)
);
```

**Key index:** `(company_id)`, `(erp_order_id)`, `(status)`, `(created_at DESC)`

---

#### `deliveries`
One delivery per order. Tracks driver assignment and all lifecycle timestamps.

```sql
CREATE TABLE public.deliveries (
    id                          uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    order_id                    uuid NOT NULL UNIQUE REFERENCES orders(id),
    company_id                  uuid NOT NULL REFERENCES companies(id),
    driver_id                   uuid,
    status                      varchar(20) DEFAULT 'UNSCHEDULED',
    -- Timestamps per state transition
    assigned_at                 timestamp,
    picked_up_at                timestamp,
    in_transit_at               timestamp,
    completed_at                timestamp,
    failed_at                   timestamp,
    cancelled_at                timestamp,
    -- Failure / cancellation metadata
    failure_code                varchar(30),   -- CLIENT_ABSENT | REFUSED | WRONG_ADDRESS | DAMAGED | OTHER
    fail_reason                 text,
    cancel_reason               text,
    cancelled_by                varchar(10),   -- CLIENT | DRIVER | SYSTEM | ADMIN
    -- SLA timing (minutes)
    waiting_sla_minutes         integer,       -- time from creation to assignment
    assign_sla_minutes          integer,       -- time from assignment to pickup
    pickup_sla_minutes          integer,       -- time from pickup to transit start
    transit_sla_minutes_computed integer,
    -- Route geometry (OSRM)
    route_geometry              text,
    route_distance_km           numeric(10,3),
    route_duration_minutes      integer,
    route_eta_at                timestamp,
    route_last_computed_at      timestamp,
    route_provider              varchar(20),
    -- COD
    cod_collected               boolean,
    cod_amount_collected        numeric(10,3),
    -- Optimistic lock
    version                     bigint DEFAULT 0,
    created_at                  timestamp DEFAULT now(),
    updated_at                  timestamp DEFAULT now(),
    CONSTRAINT ck_deliveries_status CHECK (status IN (
        'UNSCHEDULED','SCHEDULED','PICKED_UP','IN_TRANSIT',
        'DELIVERED','PARTIALLY_DELIVERED','FAILED','CANCELLED'))
);
```

---

#### `delivery_status_history`
Immutable audit trail of every status change.

```sql
CREATE TABLE public.delivery_status_history (
    id              uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    delivery_id     uuid NOT NULL REFERENCES deliveries(id),
    status          varchar(20) NOT NULL,
    changed_by      varchar(100),
    changed_by_role varchar(10),   -- CLIENT | DRIVER | DISPATCHER | MANAGER | ADMIN | SYSTEM
    note            text,
    changed_at      timestamp DEFAULT now()
);
```

---

#### `routes`
A daily route assigned to one driver + vehicle.

```sql
CREATE TABLE public.routes (
    id                          uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    company_id                  uuid NOT NULL REFERENCES companies(id),
    name                        varchar(150) NOT NULL,
    driver_id                   uuid NOT NULL,
    vehicle_id                  uuid REFERENCES vehicles(id),
    depot_id                    uuid REFERENCES depots(id),
    zone_id                     uuid REFERENCES zones(id),
    date                        date NOT NULL,
    status                      varchar(20) DEFAULT 'DRAFT',  -- DRAFT | VALIDATED | IN_PROGRESS | CLOSED | CANCELLED
    planned_start_time          time DEFAULT '08:00:00',
    planned_end_time            time DEFAULT '18:00:00',
    city                        varchar(100),
    -- Execution
    started_at                  timestamp,
    departure_time              timestamp,
    closed_at                   timestamp,
    validated_at                timestamp,
    -- OSRM results
    total_duration_seconds      integer,
    total_distance_meters       integer,
    route_geometry              text,
    is_optimized                boolean DEFAULT false,
    -- KPIs
    cumulative_delay_minutes    integer,
    route_on_time_completion_rate numeric(5,2),
    -- Metadata
    route_version               integer DEFAULT 1,
    parent_route_id             uuid,
    version                     integer DEFAULT 0,
    locked                      boolean DEFAULT false,
    created_by                  varchar(100) NOT NULL,
    created_at                  timestamp DEFAULT now(),
    updated_at                  timestamp DEFAULT now()
);
```

---

#### `route_stops`
One row per delivery within a route. Tracks arrival, SLA, and handoff.

```sql
CREATE TABLE public.route_stops (
    id                      uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    route_id                uuid NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    delivery_id             uuid NOT NULL REFERENCES deliveries(id),
    stop_order              integer NOT NULL,
    status                  varchar(20) DEFAULT 'PENDING',
    -- Time windows
    start_time_window       time,
    end_time_window         time,
    eta_at                  timestamp,
    sla_deadline            timestamp,
    -- Execution
    arrived_at              timestamp,
    actual_arrival_at       timestamp,
    completed_at            timestamp,
    actual_dwell_minutes    integer,
    dwell_minutes           integer DEFAULT 10,
    buffer_minutes          integer DEFAULT 30,
    -- SLA outcome
    sla_status              varchar(20),               -- ON_TIME | EARLY | LATE
    completion_status       varchar(10),
    -- OSRM leg
    drive_duration_seconds  integer,
    drive_distance_meters   integer,
    route_geometry          text,
    -- Removal tracking
    removed_at              timestamp,
    removed_reason          text,
    removed_by              varchar(100),
    -- QR handoff
    requires_handoff        boolean DEFAULT false,
    handoff_from_driver_id  uuid,
    handoff_to_driver_id    uuid,
    handoff_confirmed_at    timestamp,
    handoff_token           varchar(100),
    handoff_token_expires_at timestamp,
    notes                   text,
    created_at              timestamp DEFAULT now(),
    updated_at              timestamp DEFAULT now(),
    CONSTRAINT ck_route_stops_status CHECK (status IN (
        'PENDING','SCHEDULED','PICKED_UP','IN_TRANSIT','ARRIVED',
        'COMPLETED','FAILED','PARTIAL','FAILED_ATTEMPT',
        'REMOVED_REPLANNED','REMOVED_CANCELLED'))
);
```

---

#### `outbox_event`
Transactional outbox for reliable async processing.

```sql
CREATE TABLE public.outbox_event (
    id           uuid PRIMARY KEY,
    event_type   varchar(50)  NOT NULL,   -- ERP_SYNC_STOCK | ERP_SYNC_FAILURE | ERP_SYNC_CANCELLATION | INCREMENT_DRIVER_STAT
    payload      text         NOT NULL,   -- JSON
    status       varchar(20)  DEFAULT 'PENDING',   -- PENDING | PROCESSING | PROCESSED | FAILED
    retry_count  integer      DEFAULT 0,
    last_error   text,
    created_at   timestamp    NOT NULL,
    processed_at timestamp,
    INDEX (status, created_at)            -- FOR UPDATE SKIP LOCKED query
);
```

---

#### `processed_requests`
Request deduplication (idempotency layer 2).

```sql
CREATE TABLE public.processed_requests (
    idempotency_key  varchar(100) PRIMARY KEY,   -- SHA-256(scope::key)
    response_status  integer NOT NULL,
    response_body    text,
    created_at       timestamp DEFAULT CURRENT_TIMESTAMP
);
```

---

#### `proof_of_delivery`
POD record — one per delivered delivery. Photos stored in MinIO.

```sql
CREATE TABLE public.proof_of_delivery (
    id                       uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    delivery_id              uuid NOT NULL UNIQUE REFERENCES deliveries(id),
    bon_livraison_photo_url  varchar(500),   -- MinIO signed URL
    photo_url                varchar(500),   -- Package photo
    signature_url            varchar(500),
    comment                  text,
    lat                      numeric(10,7),
    lng                      numeric(10,7),
    collected_at             timestamp DEFAULT now()
);
```

---

#### Other tables

| Table | Purpose |
|-------|---------|
| `companies` | Tenant registry — ERP config, branding |
| `vehicles` | Fleet: TRUCK / VAN / CAR / MOTO, payload, plate |
| `depots` | Warehouse/depot locations (lat/lng) |
| `zones` | Geographic zones with JSONB city + postal code lists |
| `tracking` | Real-time GPS pings per delivery |
| `delivery_reports` | Driver incident reports |
| `route_report` | Immutable JSON closure report (KPIs, POD gallery, timeline) |
| `route_alerts` | SLA breach alerts (APPROACHING / AT_RISK / BREACHED) |
| `audit_logs` | Admin action log |
| `system_settings` | Key-value config store per company |
| `time_slots` | Delivery time window slots per zone |
| `slot_assignments` | Delivery-to-slot assignment |
| `vehicle_inspections` | Pre-trip inspection records |

---

## 11.2 `app_db` (AppBackend + AuthServer)

Authentication and admin user management.

```sql
CREATE TABLE public.admin_users (
    id            uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    name          varchar(100) NOT NULL,
    email         varchar(100) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL,   -- ADMIN | DISPATCHER | MANAGER | SUPER_ADMIN
    active        boolean      DEFAULT true,
    company_id    uuid,                    -- null = SUPER_ADMIN
    created_at    timestamp    DEFAULT now()
);

CREATE TABLE public.clients (
    id               uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    name             varchar(100) NOT NULL,
    phone            varchar(20)  NOT NULL UNIQUE,
    password_hash    varchar(255) NOT NULL,
    email            varchar(100),
    address          varchar(255),
    phone_verified   boolean DEFAULT false,
    odoo_partner_id  integer,
    company_id       uuid,
    created_at       timestamp DEFAULT now(),
    updated_at       timestamp DEFAULT now()
);

-- OTP tables for phone-based auth
CREATE TABLE public.client_otp (
    id          uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    phone       varchar(20) NOT NULL,
    code        varchar(6)  NOT NULL,
    expires_at  timestamp   NOT NULL,
    used        boolean     DEFAULT false,
    created_at  timestamp   DEFAULT now()
);
```

---

## 11.3 `driver_db` (DriverService)

Driver accounts, stats, and FCM tokens.

```sql
CREATE TABLE public.drivers (
    id               uuid PRIMARY KEY,
    name             varchar(100) NOT NULL,
    phone            varchar(20)  NOT NULL UNIQUE,
    password_hash    varchar(255) NOT NULL,
    active           boolean NOT NULL,
    available        boolean NOT NULL,
    current_lat      numeric(10,7),
    current_lng      numeric(10,7),
    last_location_at timestamp,
    fcm_token        varchar(500),    -- Firebase push token
    company_id       uuid,            -- null = shared pool
    created_at       timestamp NOT NULL,
    updated_at       timestamp NOT NULL
);

CREATE TABLE public.driver_stats (
    id               uuid PRIMARY KEY,
    driver_id        uuid NOT NULL,
    delivered        integer,
    failed           integer,
    cancelled        integer,
    total_deliveries integer,
    updated_at       timestamp
);

CREATE TABLE public.driver_history (
    id          uuid PRIMARY KEY,
    driver_id   uuid NOT NULL,
    delivery_id varchar(255) NOT NULL,
    status      varchar(255) NOT NULL,
    created_at  timestamp NOT NULL
);

CREATE TABLE public.driver_otp (
    id          uuid PRIMARY KEY,
    phone       varchar(20) NOT NULL,
    code        varchar(6)  NOT NULL,
    expires_at  timestamp   NOT NULL,
    used        boolean     NOT NULL,
    created_at  timestamp   NOT NULL
);
```

---

## 11.4 Entity Relationship Summary

```
companies ──< orders ──── deliveries ──< delivery_status_history
    │              │            │
    │              └── zone_id  ├── proof_of_delivery
    │                           ├── route_stops >── routes
    │                           └── tracking
    ├──< routes ──< route_stops
    ├──< vehicles
    ├──< depots
    └──< zones ──< time_slots ──< slot_assignments

[driver_db] drivers ──< driver_stats
[app_db]    admin_users, clients
```

All cross-database joins are resolved at application level — no foreign keys cross database boundaries.
