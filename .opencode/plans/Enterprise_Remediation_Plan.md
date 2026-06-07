 
## Backend Needs (Flagged Only)

| Need | Endpoint | Priority |
|------|----------|----------|
| Batch stop assignment | `POST /api/admin/routes/{id}/stops/batch` | High |
| Server-side pagination: routes | `GET /api/admin/routes?page=&size=` | High |
| Server-side pagination: drivers | `GET /api/admin/fleet/drivers?page=&size=` | High |
| Server-side pagination: vehicles | `GET /api/admin/vehicles?page=&size=` | Medium |
| Server-side pagination: depots | `GET /api/v1/depots/active?page=&size=` | Medium |
| Server-side pagination: companies | `GET /api/admin/companies?page=&size=` | Low |
| WebSocket: exceptions | `/topic/admin.exceptions` | High |
| WebSocket: deliveries | `/topic/admin.deliveries` | High |
| Nominatim proxy | `GET /api/admin/geocode/search?q=` | Medium |
| Undo cancel delivery | `POST /api/admin/deliveries/{id}/undo-cancel` | Medium |

---

## Execution Order

```
Phase 1 (Security)          ← No dependencies, do first
Phase 2 (Design System)     ← Depends on Phase 1
Phase 3 (Shared Components) ← Depends on Phase 2
Phase 4 (i18n)              ← Parallel with Phase 3
Phase 5 (Accessibility)     ← Depends on Phase 3
Phase 6 (Page Migrations)   ← Depends on Phase 3 + 5
Phase 7 (Hook Refactoring)  ← Parallel with Phase 6
Phase 8 (Data/Pagination)   ← Depends on Phase 6
Phase 9 (Polish)            ← Depends on Phase 6 + 7
Phase 10 (Low items)        ← Last
```
