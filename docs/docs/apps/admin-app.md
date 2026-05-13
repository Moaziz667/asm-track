---
id: admin-app
title: Admin App
sidebar_position: 1
---

# Admin App

**Tech:** Next.js 14 (App Router) · Mantine v7 · Tailwind CSS  
**Port:** `3000` (dev) / served via Nginx in production  
**Connects to:** API Gateway on port `80`

## Responsibility

The web back-office used by dispatchers and logistics managers to manage the full delivery operation — from ERP import to route building to real-time monitoring.

## Pages

| Route | Purpose |
|---|---|
| `/import` | ERP order import — list pending Odoo orders, bulk select and import |
| `/deliveries` | Delivery table — filter, search, assign, cancel |
| `/deliveries/[id]` | Delivery detail — order info, history, POD, bon de livraison PDF |
| `/dispatch-desk` | Real-time ops board — Kanban lanes + live map |
| `/routes` | Route builder — drag-drop stops, optimize, validate |
| `/routes/[id]` | Route detail — stop list, map, ETAs, driver location |
| `/routes-table` | Route list table |
| `/operations` | Operations tab — SLA metrics, progress bars |
| `/reports` | Analytics — KPIs, charts, driver performance |
| `/performance` | Performance analysis with charts |
| `/drivers` | Driver management |
| `/vehicles` | Vehicle fleet |
| `/zones` | Zone map editor |
| `/clients` | Client directory |
| `/settings` | Company settings, SLA thresholds |
| `/import` | ERP import with bulk selection |

## Real-time Stack

The admin app maintains a persistent **STOMP WebSocket** connection via `AlertsProvider.tsx`:

```
connects to: ws://{host}/ws (SockJS fallback)

subscribes to:
  /topic/admin/{companyId}/deliveries  → delivery events
  /topic/admin/{companyId}/routes      → route events
  /topic/admin/{companyId}/erp         → ERP import notifications
```

Events are handled by `EVENT_MAP` in `AlertsProvider.tsx` and displayed via:
- **AlertBell** — notification badge + dropdown (top-right navbar)
- **Mantine toast** — auto-dismissing popup for every event
- **Page-level polling** — import page auto-refreshes every 45s

## Key Components

| Component | Description |
|---|---|
| `AlertsProvider.tsx` | WebSocket context — manages all real-time notifications |
| `AlertBell.tsx` | Notification bell with unread count and dropdown |
| `NewDeliveryPanel.tsx` | Slide-out panel for manual delivery creation with ERP autocomplete |
| `ClientDetailSheet.tsx` | Side sheet showing client info and order history |
| `SplitLayout.tsx` | Responsive two-panel layout with mobile tab switcher |
| `DataTable.tsx` | Reusable sortable/filterable table with keyboard navigation |
| `StatusBadge.tsx` | Delivery status chip (re-exports `data-display/StatusBadge`) |
| `AvailabilityBadge.tsx` | Driver availability chip |

## Design System

- **Brand color:** `#FF5722` (orange) — used for primary actions and active states
- **CSS variables:** `--surface-1/2/3`, `--text-strong/soft/muted`, `--border-color`, `--brand-orange`
- **Dark mode:** Via `[data-mantine-color-scheme='dark']` selector + CSS variables
- **Typography:** JetBrains Mono for IDs/amounts, Inter for UI text
- **Border radius:** `2px` for data elements, `4px` for cards, `8px` for modals

## Mobile Responsiveness

All sidebar pages use the `SplitLayout` component or a manual `mobileTab` state pattern:
- Desktop: side-by-side filter panel + content
- Mobile (< lg): tab switcher "Filtres / Liste" — one panel visible at a time
