---
id: erp-adapter
title: ERP Adapter Service
sidebar_position: 4
---

# ERP Adapter Service

**Port:** `8088`  
**No database** — stateless, reads config from IAM Service at runtime  
**Swagger:** `http://localhost:8088/swagger-ui.html`

## Responsibility

Translates between ASM Track's internal domain and external ERP systems. Completely isolated — the Delivery Service never knows which ERP is behind it. Adding a new ERP = adding a new adapter class, no changes elsewhere.

## Architecture

Uses **Hexagonal Architecture (Ports & Adapters)**:

```
Controllers → CompanyAdapterFactory → [Concrete Adapter] → ERP API
                    ↑
             CompanyConfigResolver
             (fetches ERP config from IAM Service, 5-min cache)
```

## Supported ERPs

| ERP | Status | Notes |
|---|---|---|
| **Odoo 16** | Full | JSON-RPC, sale.order, stock.picking, res.partner, product.product |
| **DUX Enterprise** | Stub | Interfaces implemented, awaiting API documentation |
| **Any ERP** | Extensible | Implement 3 interfaces + add 1 factory case |

## API Endpoints

All endpoints require `X-Internal-Secret` header. Pass `X-Company-Id` to scope to a company's ERP.

### Lookup

| Method | Path | Description |
|---|---|---|
| GET | `/api/erp/lookup/pending-orders` | Fetch confirmed orders not yet exported |
| GET | `/api/erp/lookup/pending-orders/{erpOrderId}` | Full order preview with line items |
| GET | `/api/erp/lookup/clients` | Search ERP customers |
| GET | `/api/erp/lookup/products` | Search ERP products |

### Sync (outbound)

| Method | Path | Description |
|---|---|---|
| POST | `/api/erp/sync/full-delivery` | Validate full stock picking in ERP |
| POST | `/api/erp/sync/partial-delivery` | Validate partial quantities → creates backorder |
| POST | `/api/erp/sync/order-cancellation` | Cancel sale order in ERP |
| POST | `/api/erp/sync/failure` | Post failure note on ERP order |

## Odoo Integration Details

| Odoo Model | Used for |
|---|---|
| `sale.order` | List pending orders, get order details |
| `sale.order.line` | Fetch line items with quantities and prices |
| `stock.picking` | Validate delivery, create backorders |
| `res.partner` | Search clients, resolve shipping address |
| `product.product` | Search products, get weight |
| `account.payment.term` | Detect COD (Immediate Payment) |

## Adding a New ERP (Step-by-step)

```java
// 1. Create adapter classes
public class SapLookupAdapter implements ErpLookupPort {
    @Override public List<ErpClientDTO> searchClients(String search, int limit) { ... }
    @Override public List<ErpProductDTO> searchProducts(String search, int limit) { ... }
    @Override public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) { ... }
    @Override public ErpPendingOrderPreviewDTO getPendingOrderPreview(String id) { ... }
}

// 2. Add one case in CompanyAdapterFactory
case "SAP" -> new SapLookupAdapter(cfg.apiUrl(), cfg.apiKey());

// 3. Set erpType = "SAP" for the company in the DB
// That's it — no changes in Delivery Service or admin app
```
