# Database Schema — mapper_clienta

```mermaid
erDiagram
    deliveries {
        uuid id PK
        varchar externalReference UK "WH/OUT/00001 — Odoo name or Shopify order"
        varchar externalId "Odoo numeric id / Shopify order id"
        varchar status "READY | CANCELLED | ..."
        varchar sourceSystem "ODOO | SHOPIFY"
        jsonb identity "sourceSystem, externalReference, externalId"
        jsonb planning "scheduledDate, deadline, priority"
        jsonb origin "warehouseId, warehouseName, address"
        jsonb destination "name, address, city, country, phone"
        jsonb load "items[], totalWeight, totalVolume"
        jsonb financial "orderId, currency, totalAmount, items[]"
        jsonb metadata "externalId, createdAt, syncedAt"
        timestamptz sourceWriteDate "Odoo write_date — used for delta sync"
        timestamptz createdAt
        timestamptz updatedAt
    }

    sync_metadata {
        varchar key PK "e.g. last_sync_timestamp"
        timestamptz value "timestamp of last successful sync"
    }

    sync_logs {
        int id PK
        timestamptz startedAt
        timestamptz completedAt
        varchar status "running | success | failed"
        int fetched
        int created
        int updated
        int skipped
        text error
    }
```

## Table Descriptions

### `deliveries`
Core table — one row per canonical delivery from any source system.
- `externalReference` — unique business key used for upsert conflict detection (e.g. `WH/OUT/00017`, `#1001`)
- `sourceSystem` — isolates Odoo rows from Shopify rows (`ODOO` | `SHOPIFY`)
- `sourceWriteDate` — Odoo `write_date` / Shopify `updated_at` — if unchanged the row is skipped (no redundant publish)
- JSONB columns store the full canonical delivery model — flexible, no joins needed

### `sync_metadata`
Key/value store with a single row: `key='last_sync_timestamp'`.  
Read at the start of each sync cycle as the `?since=` param, updated after success.

### `sync_logs`
Audit trail — one row per sync run (every 30s).  
Tracks `fetched / created / updated / skipped` counts, duration, and any error message.
