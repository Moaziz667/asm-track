---
id: databases
title: Databases
sidebar_position: 2
---

# Databases

Three separate PostgreSQL instances — one per microservice. This enforces service boundaries and allows independent scaling.

## IAM Database (`app_db` — port 5435)

Owned by **IAM Service**.

| Table | Description |
|---|---|
| `users` | Admin user accounts (name, email, hashed password, role, companyId) |
| `companies` | Company records (name, ERP config, logo URL) |
| `clients` | End-client accounts (phone, name, odooPartnerId) |
| `refresh_tokens` | Active refresh tokens for rotation |

## Delivery Database (`delivery_db` — port 5434)

Owned by **Delivery Service**. This is the largest and most complex database.

| Table | Description |
|---|---|
| `orders` | Orders (source, client info, ERP ref, isCod, items as JSONB, status) |
| `deliveries` | Delivery tasks linked to orders (status, driver, route, COD tracking) |
| `delivery_status_history` | Every status change with actor, role, timestamp, note |
| `routes` | Multi-stop routes (driver, vehicle, date, status, city) |
| `route_stops` | Individual stops in a route (order, delivery, time windows, ETA) |
| `vehicles` | Fleet vehicles (plate, type, capacity) |
| `zones` | Delivery zones with GeoJSON polygons |
| `depots` | Pickup/origin locations |
| `audit_logs` | Compliance audit trail |
| `proof_of_deliveries` | POD records (signature, recipient, photo URLs) |

## Driver Database (`driver_db` — port 5437)

Owned by **Driver Service**.

| Table | Description |
|---|---|
| `drivers` | Driver accounts (phone, password hash, FCM token, duty status) |
| `driver_locations` | GPS location history |
| `refresh_tokens` | Driver JWT refresh tokens |

## Connecting Locally

```bash
# Delivery DB
psql -h localhost -p 5434 -U delivery -d delivery_db

# IAM DB
psql -h localhost -p 5435 -U app_user -d app_db

# Driver DB
psql -h localhost -p 5437 -U driver -d driver_db
```
