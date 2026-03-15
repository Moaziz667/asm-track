# Delivery Service — Full Specification 

---

## Position in the System

The Delivery Service is the operational core of the ASM Delivery platform. It sits behind the API Gateway and is never directly accessible from outside. It receives orders from two sources: the client app via REST and Odoo-created orders via RabbitMQ from the Mapper Service. It owns the full delivery lifecycle from order creation to completion.

---

## Boundaries

**Owns:**

- Driver identity and authentication
- Order lifecycle
- Delivery lifecycle
- GPS tracking
- Driver availability
- Delivery status history

**Does NOT own:**

- Client identity → App Backend
- Product catalog → Odoo directly
- Status validation and timeout logic → Workflow Service
- Push notifications → Notification Service
- Odoo integration → Mapper Service
- Business integrations → n8n

---

## How It Fits

`API Gateway
     ↓
Delivery Service ← RabbitMQ ← Mapper Service
     ↓
RabbitMQ → Workflow Service
         → Notification Service
         → n8n (Phase 3)`

---

## Two Order Entry Points

### Entry Point 1 — Client App (REST)

Client sends a simple JSON via REST. No canonical model involved. Client already speaks the system's language.

`Client App
     ↓
POST /api/orders (REST)
     ↓
Delivery Service maps CreateOrderRequest
     ↓
INSERT Order + Delivery entities
     ↓
Publish delivery.created`

### Entry Point 2 — Odoo via Mapper (RabbitMQ)

Mapper publishes a CanonicalDelivery message. Delivery Service consumes it and maps it to internal entities. CanonicalDelivery never touches the DB.

`Mapper Service
     ↓
RabbitMQ: orders.created (CanonicalDelivery JSON)
     ↓
Delivery Service consumes
     ↓
Maps CanonicalDelivery → Order entity + Delivery entity
     ↓
INSERT Order + Delivery
     ↓
Publish delivery.created`

---

## Database Schema

### drivers

sql

`CREATE TABLE drivers (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name              VARCHAR(100) NOT NULL,
  phone             VARCHAR(20) UNIQUE NOT NULL,
  password_hash     VARCHAR(255) NOT NULL,
  available         BOOLEAN NOT NULL DEFAULT true,
  current_lat       NUMERIC(10,7),
  current_lng       NUMERIC(10,7),
  last_location_at  TIMESTAMP,
  created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);`

### driver_otp

sql

`CREATE TABLE driver_otp (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  phone       VARCHAR(20) NOT NULL,
  code        VARCHAR(6) NOT NULL,
  expires_at  TIMESTAMP NOT NULL,
  used        BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);`

### orders

sql

`CREATE TABLE orders (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Source tracking
  source                VARCHAR(10) NOT NULL CHECK (source IN ('app', 'odoo')),
  schema_version        VARCHAR(10) NOT NULL DEFAULT '1.0.0',

  -- Client info
  -- For app orders: populated from JWT (X-User-Id header)
  -- For odoo orders: populated from CanonicalDelivery.destination.contact
  client_id             VARCHAR(100),
  client_name           VARCHAR(100) NOT NULL,
  client_phone          VARCHAR(20),
  client_email          VARCHAR(100),

  -- ERP reference
  -- Null for app orders
  -- Populated from CanonicalDelivery.identity.externalReference for odoo orders
  erp_order_id          VARCHAR(100) UNIQUE,
  erp_external_ref      VARCHAR(100),

  -- Origin (warehouse/supplier)
  -- For app orders: hardcoded from system config
  -- For odoo orders: from CanonicalDelivery.origin
  origin_name           VARCHAR(100),
  origin_address        TEXT,
  origin_city           VARCHAR(100),
  origin_postal_code    VARCHAR(20),
  origin_country_code   VARCHAR(2),
  origin_contact_name   VARCHAR(100),
  origin_contact_phone  VARCHAR(20),
  origin_contact_email  VARCHAR(100),

  -- Destination
  -- For app orders: from CreateOrderRequest (GPS + reverse geocoded address)
  -- For odoo orders: from CanonicalDelivery.destination
  dropoff_address       TEXT NOT NULL,
  dropoff_city          VARCHAR(100),
  dropoff_postal_code   VARCHAR(20),
  dropoff_country_code  VARCHAR(2) DEFAULT 'TN',
  dropoff_lat           NUMERIC(10,7),
  dropoff_lng           NUMERIC(10,7),
  delivery_instructions TEXT,

  -- Financial
  -- For app orders: from CreateOrderRequest
  -- For odoo orders: from CanonicalDelivery.financial
  total_amount          NUMERIC(10,3) NOT NULL,
  currency              VARCHAR(3) NOT NULL DEFAULT 'TND',
  payment_type          VARCHAR(10) NOT NULL CHECK (payment_type IN ('COD', 'PREPAID')),
  amount_to_collect     NUMERIC(10,3) NOT NULL DEFAULT 0,

  -- Planning
  -- For app orders: null (immediate)
  -- For odoo orders: from CanonicalDelivery.planning.scheduledAt
  scheduled_at          TIMESTAMP,
  priority              VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH')),

  -- Items
  -- Stored as JSONB for flexibility
  -- For app orders: from CreateOrderRequest.items
  -- For odoo orders: from CanonicalDelivery.load.items
  items                 JSONB NOT NULL,
  total_quantity        INTEGER NOT NULL DEFAULT 0,
  total_weight_kg       NUMERIC(10,3) NOT NULL DEFAULT 0,

  -- Status
  status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',

  -- Metadata
  -- For odoo orders: from CanonicalDelivery.metadata
  last_synced_at        TIMESTAMP,

  created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_client_id ON orders(client_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_source ON orders(source);
CREATE INDEX idx_orders_erp_order_id ON orders(erp_order_id);
CREATE INDEX idx_orders_created_at ON orders(created_at DESC);`

### deliveries

sql

`CREATE TABLE deliveries (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id        UUID NOT NULL REFERENCES orders(id),
  driver_id       UUID REFERENCES drivers(id),

  -- Status
  status          VARCHAR(20) NOT NULL DEFAULT 'WAITING_DRIVER',

  -- Timestamps per step
  assigned_at     TIMESTAMP,
  picked_up_at    TIMESTAMP,
  in_transit_at   TIMESTAMP,
  completed_at    TIMESTAMP,
  failed_at       TIMESTAMP,
  cancelled_at    TIMESTAMP,

  -- Failure / cancellation info
  fail_reason     TEXT,
  cancel_reason   TEXT,
  cancelled_by    VARCHAR(10) CHECK (cancelled_by IN ('client', 'driver', 'system')),

  created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_deliveries_order_id ON deliveries(order_id);
CREATE INDEX idx_deliveries_driver_id ON deliveries(driver_id);
CREATE INDEX idx_deliveries_status ON deliveries(status);`

### delivery_status_history

sql

`CREATE TABLE delivery_status_history (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id   UUID NOT NULL REFERENCES deliveries(id),
  status        VARCHAR(20) NOT NULL,
  changed_by    VARCHAR(100),
  changed_by_role VARCHAR(10) CHECK (changed_by_role IN ('client', 'driver', 'system')),
  note          TEXT,
  changed_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_history_delivery_id ON delivery_status_history(delivery_id);`

### tracking

sql

`CREATE TABLE tracking (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id   UUID NOT NULL REFERENCES deliveries(id),
  lat           NUMERIC(10,7) NOT NULL,
  lng           NUMERIC(10,7) NOT NULL,
  timestamp     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tracking_delivery_id ON tracking(delivery_id);
CREATE INDEX idx_tracking_timestamp ON tracking(timestamp DESC);`

### delivery_reports

sql

`CREATE TABLE delivery_reports (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id   UUID NOT NULL REFERENCES deliveries(id),
  driver_id     UUID NOT NULL REFERENCES drivers(id),
  report_type   VARCHAR(30) NOT NULL,
  description   TEXT,
  created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);`

---

## CanonicalDelivery → Internal Entities Mapping

This is the mapping applied when an Odoo order arrives via RabbitMQ.

### CanonicalDelivery → Order entity

| Order field | CanonicalDelivery source | Note |
| --- | --- | --- |
| id | generated | fresh UUID |
| source | hardcoded "odoo" | always |
| schema_version | identity.schemaVersion |  |
| client_id | null | no system client for Odoo orders |
| client_name | destination.contact.name |  |
| client_phone | destination.contact.phone |  |
| client_email | destination.contact.email |  |
| erp_order_id | metadata.externalId | Odoo integer ID |
| erp_external_ref | identity.externalReference | e.g SO/2026/001 |
| origin_name | origin.name |  |
| origin_address | origin.address.fullAddress |  |
| origin_city | origin.address.city |  |
| origin_postal_code | origin.address.postalCode |  |
| origin_country_code | origin.address.countryCode |  |
| origin_contact_name | origin.contact.name |  |
| origin_contact_phone | origin.contact.phone |  |
| origin_contact_email | origin.contact.email |  |
| dropoff_address | destination.address.fullAddress |  |
| dropoff_city | destination.address.city |  |
| dropoff_postal_code | destination.address.postalCode |  |
| dropoff_country_code | destination.address.countryCode |  |
| dropoff_lat | null | Odoo has no GPS |
| dropoff_lng | null | Odoo has no GPS |
| delivery_instructions | destination.deliveryInstructions |  |
| total_amount | financial.totalAmount |  |
| currency | financial.currency |  |
| payment_type | financial.paymentType |  |
| amount_to_collect | financial.amountToCollect |  |
| scheduled_at | planning.scheduledAt |  |
| priority | planning.priority |  |
| items | load.items (as JSONB) |  |
| total_quantity | load.totalQuantity |  |
| total_weight_kg | load.totalWeightKg |  |
| status | mapped from canonical status | READY → PENDING |
| last_synced_at | metadata.lastSyncedAt |  |

### CanonicalDelivery → Delivery entity

| Delivery field | Source | Note |
| --- | --- | --- |
| id | generated | fresh UUID |
| order_id | newly created order.id |  |
| driver_id | null | not assigned yet |
| status | WAITING_DRIVER | always on creation |
| all timestamps | null | filled as lifecycle progresses |

---

## Items JSONB Structure

Stored in orders.items as JSONB array:

json

`[
  {
    "id": "string",
    "sku": "string",
    "name": "string",
    "quantity": 2,
    "quantityDone": 0,
    "unitWeightKg": 1.2,
    "unitPrice": 1.20
  }
]
```

For app orders `unitPrice` is included. For Odoo orders all fields come from `load.items` in CanonicalDelivery.

---

## Status Machine
```
PENDING
     ↓ delivery task created
WAITING_DRIVER
     ↓ first driver accepts (atomic)
ASSIGNED
     ↓ driver confirms pickup
PICKED_UP
     ↓ driver starts moving
IN_TRANSIT
     ↓ driver confirms delivery
DELIVERED

PENDING         → CANCELLED (client cancels before delivery created)
WAITING_DRIVER  → CANCELLED (client cancels)
ASSIGNED        → CANCELLED (client or driver cancels)
ASSIGNED        → WAITING_DRIVER (Workflow timeout → driver released)
PICKED_UP       → FAILED (driver reports issue)
IN_TRANSIT      → FAILED (driver reports issue)
```

Every transition inserts a row in delivery_status_history.

---

## Cancellation Rules
```
PENDING / WAITING_DRIVER
  Client → CANCELLED, free

ASSIGNED
  Client → CANCELLED, driver released (available = true)
  Driver → delivery back to WAITING_DRIVER, driver released

PICKED_UP / IN_TRANSIT
  Client → CANNOT cancel, returns 403
  Driver → can only FAIL with reason

DELIVERED / CANCELLED / FAILED
  Nobody can do anything
```

---

## Race Condition on Accept
```
UPDATE deliveries
SET status = 'ASSIGNED', driver_id = X, assigned_at = NOW()
WHERE id = DEL-001
AND status = 'WAITING_DRIVER'

0 rows updated → 409 CONFLICT (already taken)
1 row updated  → success
```

---

## All Endpoints

### Driver Auth
```
POST /api/auth/driver/register
POST /api/auth/driver/login
POST /api/auth/driver/refresh-token
```

### Client Orders
```
POST   /api/orders
GET    /api/orders
GET    /api/orders/active
GET    /api/orders/{id}
POST   /api/orders/{id}/cancel
GET    /api/orders/{id}/cancellable
POST   /api/orders/{id}/reorder
```

### Client Delivery and Tracking
```
GET    /api/deliveries/{id}
GET    /api/deliveries/{id}/tracking
GET    /api/deliveries/{id}/history
```

### Driver Deliveries
```
GET    /api/driver/deliveries/available
GET    /api/driver/deliveries/active
GET    /api/driver/deliveries/{id}
POST   /api/driver/deliveries/{id}/accept
POST   /api/driver/deliveries/{id}/pickup
POST   /api/driver/deliveries/{id}/transit
POST   /api/driver/deliveries/{id}/complete
POST   /api/driver/deliveries/{id}/fail
POST   /api/driver/deliveries/{id}/cancel
POST   /api/driver/deliveries/{id}/report
```

### Driver Profile
```
GET    /api/driver/profile
PUT    /api/driver/profile
PUT    /api/driver/password
PUT    /api/driver/availability
GET    /api/driver/history
GET    /api/driver/stats
```

### Driver Location
```
POST   /api/driver/location
```

---

## Events Published

### delivery.created
```
deliveryId, orderId, clientId, clientName
source (app/odoo), items, totalAmount
paymentType, amountToCollect
dropoffAddress, dropoffLat, dropoffLng
scheduledAt, priority, createdAt
```

### delivery.assigned
```
deliveryId, orderId, clientId
driverId, driverName, driverPhone
assignedAt
```

### delivery.picked_up
```
deliveryId, orderId, clientId, driverId
pickedUpAt
```

### delivery.in_transit
```
deliveryId, orderId, clientId, driverId
driverLat, driverLng, inTransitAt
```

### delivery.completed
```
deliveryId, orderId, clientId, driverId
items, totalAmount, amountCollected
completedAt
```

### delivery.cancelled
```
deliveryId, orderId, clientId, driverId
cancelledBy, reason, cancelledAt
```

### delivery.failed
```
deliveryId, orderId, clientId, driverId
reason, failedAt
```

---

## Workflow Service Integration

Delivery Service publishes and moves on. It does not wait for Workflow Service response.

Workflow Service can send back correction events:
```
workflow.reset_to_waiting  → Delivery Service resets to WAITING_DRIVER + releases driver
workflow.force_cancel      → Delivery Service force cancels delivery
workflow.force_fail        → Delivery Service force fails delivery
```

---

## Security

JWT validated entirely by API Gateway. Delivery Service reads injected headers only:
```
X-User-Id
X-User-Role
```

Driver JWT signed by Delivery Service with shared JWT_SECRET.

Route protection:
```
/api/orders/**          → CLIENT only
/api/driver/**          → DRIVER only
/api/deliveries/**      → CLIENT and DRIVER
/api/auth/driver/**     → public
```

---

## Infrastructure
```
Language:    Java 17
Framework:   Spring Boot 3.x
Build:       Gradle
Database:    PostgreSQL (dedicated instance)
Messaging:   RabbitMQ (Spring AMQP)
Auth:        JWT (shared secret)
Port:        8082 (internal, not public)`