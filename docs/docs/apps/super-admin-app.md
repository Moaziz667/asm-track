---
id: super-admin-app
title: Super Admin App
sidebar_position: 2
---

# Super Admin App

**Tech:** Vite + React + TypeScript · TanStack Router/Query · Shadcn UI · Tailwind CSS  
**Purpose:** Internal ASM panel for platform-wide operations across all companies.

## Frontend scope

- Company Users: create, activate, and assign admin accounts to a company.
- Routes: global route visibility with company, driver, and vehicle filters.
- Audit Log: platform audit trail with role and action filters.

## Backend integration

- **Companies:** `GET/POST/PUT/DELETE /api/admin/companies` (super-admin only).
- **Admin users:** `GET/POST /api/admin/users`, `PATCH /api/admin/users/{id}/status`.
- **Drivers:** `GET/POST/PUT/PATCH /api/admin/drivers` (super-admin only, Driver Service).
- **Vehicles:** `GET/POST/PUT/PATCH /api/admin/vehicles` (super-admin only, Delivery Service).
- **Audit log:** `GET /api/admin/audit` for cross-company events.

## Auth and multi-tenancy

- Uses the `SUPER_ADMIN` role. Tokens may have no `companyId`, which gives global access.
- When a `companyId` exists, services scope data to that company automatically.
