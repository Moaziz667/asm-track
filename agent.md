# Agent Context — PFE Project

## Project Overview

This is a **Final Year Project (PFE)** implementing a multi-tenant, microservices-based **delivery management integration platform**.

The system pulls delivery orders from heterogeneous source systems (Odoo ERP, Shopify e-commerce), normalises them into a **shared canonical model**, persists the state in PostgreSQL, and publishes domain events to RabbitMQ for downstream consumption (transport/dispatch services).

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         ERP / E-COMMERCE SOURCES                        │
│   Odoo (JSON-RPC :8069)            Shopify (REST API)                   │
└────────────┬───────────────────────────────────┬────────────────────────┘
             │                                   │
     ┌───────▼──────┐                   ┌────────▼──────┐
     │  QueryOdoo   │ :3100             │ QueryShopify  │ :3200
     │  (NestJS)    │                   │   (NestJS)    │
     └───────┬──────┘                   └────────┬──────┘
             │  HTTP poll                        │  HTTP poll
     ┌───────▼──────┐                   ┌────────▼──────┐
     │ MappingOdoo  │ :3300             │MappingShopify │ :3400
     │  (NestJS)    │                   │   (NestJS)    │
     └──────┬───────┘                   └───────┬───────┘
            │                                   │
            └──────────────┬────────────────────┘
                           │
              ┌────────────▼────────────┐
              │       PostgreSQL        │  :5433
              │  (isolated DB/client)   │
              └────────────┬────────────┘
                           │
              ┌────────────▼────────────┐
              │        RabbitMQ         │  :5672 / UI :15672
              │   exchange: delivery.events (topic)
              │   queue:    order.ready.mapper
              │   keys:     delivery.created
              │             delivery.updated
              └─────────────────────────┘
```

---

## Services

| Service | Port | Role |
|---|---|---|
| `odoo-query` | 3100 | Fetches raw delivery records from Odoo via JSON-RPC |
| `odoo-mapper` | 3300 | Maps Odoo → CanonicalDelivery, persists, publishes events |
| `shopify-query` | 3200 | Fetches raw orders from Shopify REST API (or serves in-memory test orders) |
| `shopify-mapper` | 3400 | Maps Shopify → CanonicalDelivery, persists, publishes events |
| `postgres` | 5433 | Shared PostgreSQL instance, one DB per client |
| `rabbitmq` | 5672/15672 | Message broker for domain events |

---

## Canonical Model (`@asm/canonical-model`)

Shared TypeScript package hosted on GitLab Package Registry.
All mappers must produce this exact contract. Downstream services consume only this contract.

Key interfaces:

```typescript
CanonicalDelivery {
  identity:   DeliveryIdentity    // id, externalReference, sourceSystem, timestamps, schemaVersion
  status:     CanonicalStatus     // DRAFT | READY | IN_TRANSIT | DELIVERED | FAILED | CANCELLED
  planning:   DeliveryPlanning    // scheduledAt (ISO 8601), priority (NORMAL | HIGH)
  origin:     Origin              // warehouse name, address, contact
  destination: Destination        // recipient name, address, contact, deliveryInstructions
  load:       DeliveryLoad        // items[], totalQuantity, totalWeightKg
  financial:  DeliveryFinancial   // totalAmount, currency, paymentType (COD | PREPAID), amountToCollect
}
```

Status mappings:
- **Odoo**: `draft` → `DRAFT`, `assigned` → `READY`
- **Shopify**: `fulfillment_status = null/partial` → `READY`, `fulfilled` → skipped

---

## Sync Mechanism

Each mapper runs a **scheduled incremental sync** on startup and at a configurable interval:

1. Read `lastSyncTimestamp` from `sync_metadata` table
2. Fetch records from the Query service (since last sync)
3. Map each record to `CanonicalDelivery` (skip if `null`)
4. Upsert in PostgreSQL (transaction)
5. Publish `delivery.created` or `delivery.updated` to RabbitMQ
6. Update `sync_metadata` and write a `sync_log` entry

**Retry logic**: 3 attempts, 5 s delay between retries.

---

## Mapping Engine (standalone tooling)

A separate visual mapping configurator for creating/editing field mapping rules.

| Layer | Tech | Path |
|---|---|---|
| Backend | NestJS + TypeORM + SQLite | `MappingEngine/Backend/` |
| Frontend | Vue 3 + Vite (4-step wizard) | `MappingEngine/Frontend/` |

The engine uses a `TransformEngine` that:
1. Extracts source fields via dot-notation
2. Applies enum translation (e.g. `"assigned"` → `"READY"`)
3. Applies value transforms (`UPPERCASE`, `TO_NUMBER`, static values, …)
4. Writes to canonical dot-path
5. Validates the assembled canonical object

---

## Multi-Tenancy

Each client has its own `.env` file under `Microservices/clients/`:

| Variable | Purpose |
|---|---|
| `DB_NAME` | Isolated PostgreSQL database per client |
| `ODOO_URL / ODOO_DB / ODOO_UID / ODOO_PASSWORD` | Odoo credentials |
| `SHOPIFY_STORE_URL / SHOPIFY_ACCESS_TOKEN` | Shopify credentials (empty = test mode) |
| `WAREHOUSE_*` | Origin warehouse address injected at mapping time |
| `SYNC_INTERVAL_MS` | Polling interval (default 30 000 ms) |
| `GITLAB_TOKEN` | GitLab token to pull `@asm/canonical-model` during Docker build |

---

## Docker Compose Strategy

The infra is split into **composable files**:

```bash
# Client with Odoo + Shopify
docker compose \
  -f docker-compose.shared.yml \
  -f docker-compose.odoo.yml \
  -f docker-compose.shopify.yml \
  --env-file clients/clientA.env up -d

# First run — create client database
docker exec mapper-postgres psql -U mapper -c "CREATE DATABASE mapper_clienta;"
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Node.js 20 |
| Framework | NestJS (TypeScript) |
| ORM | TypeORM |
| Database | PostgreSQL 16 |
| Message Broker | RabbitMQ 3 (topic exchange) |
| Frontend | Vue 3 + Vite |
| Containerisation | Docker + Docker Compose |
| Package registry | GitLab Package Registry (`@asm/canonical-model`) |

---

## Key Events

| Routing Key | Trigger |
|---|---|
| `delivery.created` | New canonical delivery inserted in DB |
| `delivery.updated` | Existing delivery updated in DB |

Exchange: `delivery.events` (topic, durable)  
Queue: `order.ready.mapper` (durable, classic)

---

## Project Structure

```
PFE/
├── MappingEngine/
│   ├── Backend/          NestJS mapping configurator API
│   └── Frontend/         Vue 3 visual mapping wizard
├── Microservices/
│   ├── asm-canonical-model/     Shared canonical contract package
│   ├── clients/                 Per-client .env files
│   ├── OdooMapping/
│   │   ├── QueryOdoo/           Odoo data fetcher
│   │   └── MappingOdoo/         Odoo → canonical mapper
│   ├── ShopifyMapping/
│   │   ├── QueryShopify/        Shopify data fetcher
│   │   └── MappingShopify/      Shopify → canonical mapper
│   ├── Shared/                  Shared RabbitMQ publisher utility
│   ├── docker-compose.shared.yml
│   ├── docker-compose.odoo.yml
│   └── docker-compose.shopify.yml
└── PFE DIGRAMS/
    ├── DATABASE/          DB schema diagrams
    └── SEQUENCE/          Sequence diagrams (delivery created, update status, assign agent)
```
