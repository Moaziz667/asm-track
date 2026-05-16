# ASM Track — Technical Documentation Index

> **Code-first analysis** — every claim traces to actual source files.  
> Platform: **ASM Track** | Stack: Spring Boot 3 · Flutter · Next.js · React · PostgreSQL · MinIO · OSRM · Odoo

## Documents

| # | File | Contents |
|---|------|----------|
| 1 | [01-global-architecture.md](01-global-architecture.md) | C4 diagrams, runtime topology, infrastructure map |
| 2 | [02-service-catalog.md](02-service-catalog.md) | All 4 microservices: ports, endpoints, entity models, config |
| 3 | [03-frontend-clients.md](03-frontend-clients.md) | Admin App, Super Admin App, Flutter Driver App |
| 4 | [04-delivery-lifecycle.md](04-delivery-lifecycle.md) | State machine, all transitions, triggers, side effects |
| 5 | [05-distributed-patterns.md](05-distributed-patterns.md) | Outbox, ERP sync, offline queue, idempotency, multi-tenancy |
| 6 | [06-api-reference.md](06-api-reference.md) | All operationally important endpoints across every service |
| 7 | [07-security-model.md](07-security-model.md) | JWT strategies, roles, tenant isolation, internal secret |
| 8 | [08-infrastructure-deployment.md](08-infrastructure-deployment.md) | Docker Compose topology, env vars, volumes, ports |
| 9 | [09-runbooks.md](09-runbooks.md) | Operational playbooks: dead outbox, ERP failure, driver offline |

## Quick Reference

| Concern | Where |
|---------|-------|
| Delivery state machine | [04-delivery-lifecycle.md](04-delivery-lifecycle.md) |
| Outbox pattern (50 retries, SKIP LOCKED) | [05-distributed-patterns.md](05-distributed-patterns.md#1-transactional-outbox-pattern) |
| Odoo 19 backorder wizard fix | [05-distributed-patterns.md](05-distributed-patterns.md#2-erp-synchronization-architecture) |
| JWT claims per service | [07-security-model.md](07-security-model.md) |
| All env vars + production values | [08-infrastructure-deployment.md](08-infrastructure-deployment.md) |
| Dead-letter alert runbook | [09-runbooks.md](09-runbooks.md) |
