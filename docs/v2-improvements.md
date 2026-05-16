# V2 Improvements

Technical debt and architectural improvements identified during PFE — deferred to v2.

## Architecture

| # | Item | Why deferred |
|---|------|-------------|
| 1 | **H2 → PostgreSQL** for ErpAdapterService idempotency store | H2 is per-instance and lost on restart — PostgreSQL makes it persistent and multi-instance safe |
| 2 | **SSL pinning** in Flutter driver app | Code present but disabled for testing |
| 3 | **GPS geofence** re-enabled for POD capture | Disabled for testing — 250m max distance, 100m accuracy threshold |

## Backend

| # | Item | Why deferred |
|---|------|-------------|
| 4 | **DUX ERP adapter** implementation | Pending vendor API documentation — currently a stub returning false |
| 5 | **Vehicle inspection** backend endpoint | Flutter screen exists, no backend |
| 6 | **ConcurrentHashSet inFlight guard** made distributed | Currently per-instance only — breaks under horizontal scaling |

## Infrastructure

| # | Item | Why deferred |
|---|------|-------------|
| 7 | **Production secrets** rotation | JWT_SECRET, INTERNAL_SECRET, MinIO credentials all use defaults |
| 8 | **COOKIE_SECURE=true** | Must be enabled for HTTPS production deployment |
| 9 | **OUTBOX_ALERT_WEBHOOK_URL** configured | Dead-letter Slack alerts not wired in production |
