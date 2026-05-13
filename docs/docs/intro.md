---
id: intro
title: Introduction
sidebar_position: 1
---

# ASM Track

**ASM Track** is a last-mile delivery management platform built for logistics companies operating in Tunisia. It connects ERPs (Odoo, SAP, Sage) to a full delivery operations stack — from order import to driver GPS tracking, proof of delivery, and ERP status sync.

## What It Does

| Capability | Description |
|---|---|
| **ERP Import** | Pull confirmed orders from Odoo. Admin reviews and bulk-imports as deliveries. |
| **Dispatch** | Assign deliveries to drivers. Create and optimize multi-stop routes. |
| **Driver Mobile** | Flutter app — drivers accept, navigate, collect signatures/photos as POD. |
| **Real-time Ops** | WebSocket-powered dispatch desk. SLA alerts, lane view, exception feed. |
| **COD** | Cash on delivery tracked per delivery. Driver confirms collection, admin sees totals. |
| **ERP Sync** | Delivery outcomes sync back to Odoo (stock validation, cancellations, failures). |
| **PDF Generation** | Bon de livraison and feuille de route PDFs with company branding. |
| **Client Tracking** | Public tracking link — no login required. |

## Tech Stack

| Layer | Technology |
|---|---|
| Admin Web App | Next.js 14, Mantine v7, Tailwind CSS, STOMP WebSocket |
| Driver App | Flutter (Android/iOS) |
| API Gateway | Nginx (routing + JWT validation) |
| IAM Service | Spring Boot 3, JWT, PostgreSQL |
| Delivery Service | Spring Boot 3, PostgreSQL, MinIO, OSRM |
| Driver Service | Spring Boot 3, PostgreSQL, Firebase FCM |
| ERP Adapter | Spring Boot 3, Hexagonal architecture, Odoo JSON-RPC |
| Routing | OSRM (self-hosted, Tunisia OSM data) |
| File Storage | MinIO (S3-compatible) |
| Containerization | Docker Compose |

## Quick Start

```bash
cd Microservices
docker compose up -d
```

Services will be available at:
- Admin App: `http://localhost:3000`
- API Gateway: `http://localhost:80`
- Delivery API Swagger: `http://localhost:8082/swagger-ui.html`
- IAM API Swagger: `http://localhost:8080/swagger-ui.html`
- Driver API Swagger: `http://localhost:8086/swagger-ui.html`
- MinIO Console: `http://localhost:9001`
