# Multi-Tenant Migration Audit — branch `refactor/odoo-sync-services`

Date: 2026-07-25 · Scope: ApiGateway, AppBackend, DeliveryMicroservice, DriverService, ErpAdapterService
Verdict: **NOT production-ready.** The Hibernate schema-per-tenant core (connection provider, resolver,
filters, Rabbit stamping, TenantIterator, Feign propagation) is correctly built, but four Critical and
five High findings — all in the "everything around the DB" layer — directly explain the reported
regressions (FCM silence, inconsistent Odoo sync, random ERP failures, cross-tenant anomalies).

Legend: each finding lists Severity / Root cause / Impact / Files / Fix / Side effects / Tests / Status.

---

## CRITICAL

### C1 — Singleton single-slot Odoo uid cache shared across tenants
**Files:** `Microservices/ErpAdapterService/src/main/java/com/asm/erpadapter/adapter/odoo/OdooJsonRpcClient.java` (lines 110–148)

**Root cause.** `OdooJsonRpcClient` is a singleton and caches the resolved Odoo `uid` in ONE slot:
```java
private final AtomicReference<String> cachedUidKey = ...;
private final AtomicInteger cachedUid = ...;
...
cachedUid.set(uid);        // (1)
cachedUidKey.set(key);     // (2)  ← not atomic with (1)
```
Two tenants have different `db|login|secret` keys. Consequences:
1. **Race:** thread T-A (tenant A) executes (1); thread T-B (tenant B) executes (1)+(2); T-A executes (2).
   Result: `cachedUidKey` = A's key, `cachedUid` = B's uid. Every subsequent call for tenant A passes
   **tenant B's uid** with tenant A's db+secret. If both tenants point at the same Odoo host/db with
   different integration users, writes execute as the wrong Odoo user; otherwise Odoo returns access
   errors that surface as "random" sync failures.
2. **Thrash:** with ≥2 active tenants the key check almost always misses → a `common.authenticate`
   round-trip before nearly every RPC → latency, Odoo rate-limit exposure, and intermittent auth
   timeouts that look like flaky sync.
3. `invalidateAuthCache()` invalidates the (only) slot for whichever tenant happens to occupy it.

**Business impact.** Direct cause of "ERP synchronization randomly fails" and "works for one tenant but
fails for another". Worst case: stock moves validated in the ERP under the wrong integration identity.

**Fix (production-ready).** Replace with `ConcurrentHashMap<String, Integer> uidByCredentials` keyed by
`db + "|" + login + "|" + secret.hashCode()`; resolve with `computeIfAbsent` (single-flight per key);
`invalidateAuthCache()` removes only the current tenant's key (derived from current settings). Correct
because the key already fully identifies the credential set — per-key entries make tenant interleaving
irrelevant and preserve the "credential change re-resolves" property.

**Side effects.** Unbounded growth only in the number of distinct credential sets (= tenants) — fine.
**Tests.** Concurrency test: two threads alternate tenants (distinct settings), assert each `buildArgs`
carries the uid resolved for its own credentials; assert exactly one `authenticate` per credential set.

---

### C2 — OdooMetadataCache fetches fields on a thread without TenantContext
**Files:** `ErpAdapterService/.../adapter/odoo/capability/OdooMetadataCache.java` (lines 56–65)

**Root cause.** The single-flight uses `CompletableFuture.supplyAsync(() -> fetchFields(model))` on the
ForkJoin **common pool**. `fetchFields → rpc.buildArgs → settingsClient.getSettings()` reads
`TenantContext` — which is **null on the pool thread**. The settings resolve for `__no_tenant__`
(usually `NONE` → blank URL → empty field set), while the cache **key** was computed on the caller
thread with the correct tenant. The wrong/empty result is then cached under the correct tenant key for
**30 minutes**.

**Business impact.** Capability/field resolution silently degrades per tenant for 30-minute windows —
partial deliveries, done-quantity writes and field mapping fall back or fail. This is the signature of
"Odoo synchronization has become inconsistent": it works, then doesn't, then works again (TTL expiry).

**Fix (production-ready).** Capture the tenant before going async and restore it inside the task:
```java
UUID tenant = TenantContext.get();
CompletableFuture.supplyAsync(() -> {
    if (tenant != null) TenantContext.set(tenant);
    try { ... fetchFields(model) ... } finally { TenantContext.clear(); }
});
```
(or drop the async entirely and use `ConcurrentHashMap.computeIfAbsent` with a per-key lock — the
caller blocks on `future.get(30s)` anyway, so async buys nothing). Correct because the fetch then reads
the same tenant's settings as the key it populates.

**Side effects.** None; single-flight semantics preserved.
**Tests.** Unit test: set tenant A, stub SettingsClient to return A's config only when TenantContext==A
(else NONE); assert `getAvailableFields` returns A's fields and the cache entry is non-empty.

---

### C3 — SystemHealthSnapshotService: one shared snapshot serves every tenant (cross-tenant data leak + wrong data)
**Files:** `DeliveryMicroservice/.../service/SystemHealthSnapshotService.java` (fields `snapshot`,
`history`; `refresh()` line 90; `current()` line 73; `buildErpSync()` line 228)

**Root cause.** The service is tenant-blind:
- `@Scheduled refresh()` runs with no TenantContext → `orderRepo.countByErpSyncStatus(...)` and
  `findTop50ByErpSyncStatus...` hit the **`public` schema** (empty) → the console shows zero ERP
  failures for every tenant (silent regression of the ops console).
- `current()` builds synchronously on first call **inside a tenant's request** → that tenant's failure
  list (with `blNumber`, `erpRef`, `lastSyncError`, client-identifying refs) is stored in the single
  `volatile snapshot` / shared `history` and then **served to every other tenant** until the next
  scheduled refresh wipes it back to public-schema emptiness.

**Business impact.** Cross-tenant disclosure of order/BL references and error details; health console
data is wrong for all tenants. Violates the core isolation guarantee.

**Fix (production-ready).** Key snapshot+history by companyId (`ConcurrentHashMap<UUID, Snapshot>`);
scheduled refresh iterates via `TenantIterator.forEachActive`; `current()` returns only
`TenantContext.get()`'s entry (404/empty if none). Platform-level signals (breakers, DLQ depths, DB
reachability) can stay global but must be separated from tenant-scoped `erpSync` data.

**Side effects.** N tenants × queries per refresh — acceptable at 10s cadence for small N; raise the
interval or refresh lazily per tenant if N grows.
**Tests.** Two-tenant IT: seed SYNC_FAILED order in tenant A only; call the health endpoint with tenant
B's token → assert no A data; with A's token → assert failure visible.

---

### C4 — WebSocketSecurityInterceptor writes TenantContext on pooled STOMP threads
**Files:** `DeliveryMicroservice/.../security/WebSocketSecurityInterceptor.java` (lines 47–52, 59–61)

**Root cause.** On `CONNECT` the interceptor does `TenantContext.set(orgId)` on whatever
`clientInboundChannel` pool thread processed the frame, and clears only on `DISCONNECT` — which runs on
a **different** pool thread. STOMP frames from many sessions/tenants multiplex over the same pool, so
the ThreadLocal is (a) useless for its intended purpose and (b) a **leaked tenant** sitting on a pooled
thread. Today no `@MessageMapping` handler exists, so nothing reads it — it is a loaded gun: the first
inbound-message handler that touches JPA will execute in an arbitrary tenant's schema.

Secondary defects: it reads only the `org_id` claim (not the `organization` map fallback the gateway
uses), and `/topic/driver.{id}` subscriptions (line 114–120) check role but **not tenant** — an
ADMIN/DISPATCHER of tenant B who learns a tenant-A driver UUID can subscribe to that driver's live
event stream (see H5).

**Fix (production-ready).** Delete the `TenantContext.set/clear` from CONNECT/DISCONNECT — the
authenticated `UserPrincipal` (which already carries companyId) attached via `accessor.setUser(auth)`
is the per-session source of truth. If message handlers ever need the tenant, set/clear it per-message
in `preSend`/`afterSendCompletion` from the session principal, never per-connection.

**Tests.** Add an ArchUnit/unit test asserting no `TenantContext.set` outside the sanctioned entry
points (TenantContextFilter, PublicTrackingTenantFilter, TenantInboundPostProcessor, TenantIterator,
task decorators).

### C5 — Tenant-scoped STOMP topics rejected by the RabbitMQ broker relay (found live, post-audit)
**Files:** `DeliveryMicroservice/.../service/EventPublisher.java`, `service/route/RouteWebSocketService.java`,
`messaging/AuditEventConsumer.java`, `security/WebSocketSecurityInterceptor.java`,
`Apps/admin-app-react/src/components/RealtimeProvider.tsx`, `src/pages/route-details/useRouteData.ts`

**Root cause.** With `websocket.broker.relay.enabled=true` (the compose default), `/topic/x` maps to
RabbitMQ routing key `x` — and RabbitMQ rejects routing keys containing `/`. The Phase-3 tenant topics
(`/topic/company/{id}/admin.deliveries` etc.) therefore failed broker-side with "Invalid destination":
**every tenant-scoped realtime event was silently dropped** whenever the relay was on. This alone
explains much of "various features became unstable after the migration" (dead admin dashboards, no
live route/driver updates). The in-memory simple broker (used in tests/PoC) accepts slashes, which is
why the Phase-3 verification passed.

**Fix (production-ready).** Dot notation end-to-end: `/topic/company.{companyId}.{subtopic}` in both
publishers + the audit consumer, dot-parsing in the subscription interceptor, and matching frontend
subscriptions. `/topic/driver.{id}` and `/topic/public.{id}` were already dot-form (and already worked).
**Tests.** Live verification: no `Invalid destination` relay errors after restart; add an acceptance
assertion that a published tenant event reaches a relay-backed subscriber.

---

## HIGH

### H1 — DB layer fails OPEN to the `public` schema when tenant is missing
**Files:** `TenantIdentifierResolver.java` (×3 services, `TenantSchema.DEFAULT`), `TenantContextFilter.java` (×4)

**Root cause.** When `TenantContext` is unset, Hibernate routes to `public`. The filters deliberately
leave the context unset for header-less traffic. Net effect: **any** authenticated path that loses the
header (route misconfig, new async code, internal call bypassing the Feign interceptor) silently
reads/writes `public` instead of erroring. This is the enabling condition behind C2, C3 and the FCM
silence (H2) — bugs become invisible "empty results" instead of loud failures.

**Fix.** Fail closed at the web layer: in each service's `TenantContextFilter`, reject (403) requests to
authenticated API prefixes (`/api/**` minus an explicit public allowlist and `/internal/**`,
`/actuator/**`) that carry no `X-Company-Id`. Keep `public` fallback only for bootstrap/actuator.
Additionally emit a metric+WARN in `TenantIdentifierResolver` whenever DEFAULT is resolved outside
startup, so residual leaks are observable.

**Side effects.** Any legitimately tenant-less endpoint must be added to the allowlist — enumerate them
deliberately (that is the point).
**Tests.** Per service: authenticated request without header → 403; public tracking without header → 200.

### H2 — Driver-app FCM silence: unobservable multi-link failure chain
**Files:** `DeliveryMicroservice/.../service/FcmNotificationService.java` (lines 28–30, 50–52),
`notification/NotificationGateway.java`, `DriverService/.../controller/DriverController.java` (82–99),
`AppBackend/.../service/IamCommandApplier.java` (57–65), `AppBackend/.../scheduler/KeycloakSyncScheduler.java`

**Root cause (chain).** A push reaches a driver only if: driver's KC user is an **org member** (else the
gateway 403s `PUT /api/*/drivers/me/fcm-token` and the token is never stored) → the token row lives in
the **tenant schema** the send-path resolves → the send-path thread has TenantContext (else
`transportPort.getDriver` queries `public`, returns null) → `FcmNotificationService` finds a token.
Every link fails **silently**: missing-token is `log.debug`, transport errors are `log.warn`, there is
no metric, no dead-letter, no retry. Pre-migration, one schema + no org gating meant these links could
not fail; post-migration each is a live failure mode. Most probable production causes: (a) drivers
created before the migration lack org membership until `KeycloakSyncScheduler` reconciles; (b) tokens
registered pre-migration live in `public`/were not copied into tenant schemas; (c) any send-path that
loses tenant (see H1) resolves no driver.

**Fix.**
1. Observability first: promote missing-token and send-failure to WARN with `companyId`+`driverId`,
   add counters (`fcm.sent`, `fcm.skipped_no_token`, `fcm.error`) — this converts "notifications
   stopped" from a mystery into a dashboard.
2. Data: one-off migration check — for each tenant schema, count drivers with null `fcm_token` vs KC
   org members; backfill org memberships (the reconciler does this — verify it ran for all tenants).
3. Keep the FCM send after `withTenant` restoration (already correct in `EventPublisher`).
**Tests.** Two-tenant IT: register token as tenant-A driver, trigger `publishDeliveryScheduled` in A →
FCM send attempted with A's token; trigger in B → no A lookup. Contract test: `PUT /fcm-token` with a
token lacking org claim → 403 (documents the gateway behavior).

### H3 — MinIO: world-readable shared bucket + fail-open tenant prefix
**Files:** `DeliveryMicroservice/.../storage/MinioStorageService.java` (lines 36–48 policy, 82–86 prefix)

**Root cause.** `ensureBucket` applies `Principal:"*" / s3:GetObject` to the **entire shared bucket** —
every POD photo (signatures, addresses) of every tenant is downloadable by anyone holding the URL; the
only protection is URL unguessability, and URLs are stored in DB rows, sync payloads, logs and the ERP.
`tenantPrefix()` returns the raw path when TenantContext is null — tenant-less writes land unprefixed.

**Fix.** Private bucket + presigned GET URLs (short TTL) for POD assets; keep `company-logos` public if
branding requires. Make `tenantPrefix` throw when TenantContext is null on tenant-scoped paths.
`deleteFile(url)`/`getBytes(url)` should verify the object path starts with the current tenant's prefix
before acting (defense-in-depth against IDOR via stored URLs).
**Side effects.** ERP adapter fetches POD photos by URL — switch it to presigned URLs generated at sync
time (it already receives the URL in the command payload).
**Tests.** Upload as tenant A; fetch raw URL unauthenticated → 403; presigned → 200; delete with a
tenant-B-prefixed URL while in tenant A context → rejected.

### H4 — ErpAdapter's private H2 store: no tenant scoping, breaks under >1 instance
**Files:** `ErpAdapterService/src/main/resources/application.yml` (41–48),
`entity/IdempotentTransaction.java`, `service/IdempotencyService.java`

**Root cause.** The plan declared ErpAdapter "stateless — no DB", but it persists idempotency records
and mapping overrides in a **local H2 file** (`ddl-auto: update`). `IdempotentTransaction` has no
tenant column (txId is the Delivery outbox UUID, so cross-tenant collision is improbable but the
isolation is by caller convention, not enforcement). Worse: the H2 file is per-container — running two
adapter replicas gives each its own idempotency store → **duplicate ERP side effects on retry
rebalancing**; and `responsePayload` is capped at 2000 chars — larger results fail `saveResult`
silently (log.error), quietly disabling idempotency for that tx.

**Fix.** Move idempotency+mappings to the shared Postgres (dedicated `erp_adapter` schema with a
`tenant_id` column and unique `(tenant_id, transaction_id)`), or per-tenant schemas like the other
services. Widen `responsePayload` to TEXT. Until then, pin the adapter to a single replica and document it.
**Tests.** Replay the same sync command twice → exactly one Odoo mutation; payload >2000 chars → record
persisted (TEXT) and replay returns cached result.

### H5 — Driver STOMP topics authorize by role, not tenant
**Files:** `WebSocketSecurityInterceptor.java` (114–120), `EventPublisher.sendDriver` (153–161),
`RouteWebSocketService` (`/topic/driver.` destinations)

**Root cause.** Driver events publish to global `/topic/driver.{driverId}`; subscription is allowed for
the driver himself **or any ADMIN/DISPATCHER/MANAGER — of any tenant**. A tenant-B admin who obtains a
tenant-A driver UUID (e.g. from a leaked payload/log) streams that driver's assignments, client names
and addresses live.

**Fix.** Publish driver events under `/topic/company/{companyId}/driver.{driverId}` (the interceptor's
company-scoped branch already validates both company match and driver identity); migrate the driver app
subscription; remove the global `/topic/driver.` branch after cutover.
**Tests.** Interceptor unit tests: tenant-B admin subscribing to A's driver topic → rejected; driver
subscribing to own topic under his company → allowed.

---

## MEDIUM

### M1 — No centralized async tenant propagation
Hand-rolled `withTenant`/decorators are duplicated in `EventPublisher`, `RouteWebSocketService`,
`AsyncConfig`; raw `CompletableFuture.runAsync/supplyAsync` on the common pool appears in 4+ places and
already caused C2. **Fix:** one shared `TenantAwareTaskDecorator` + a dedicated application executor;
forbid bare `runAsync`/`supplyAsync` via ArchUnit test. Reduces the whole class of future regressions.

### M2 — Silent global-topic fallback in `tenantTopic()`
`EventPublisher`/`RouteWebSocketService` fall back to `/topic/{subtopic}` when tenant is missing; the
subscription interceptor forbids those destinations, so events are published into a void — **silent
event loss** instead of a loud failure. **Fix:** log ERROR + drop (or throw) when tenant is missing;
remove the fallback.

### M3 — ERP inbound poll cursors are in-memory
`ErpChangeOrchestrator.cursorByTenant` resets on restart and re-seeds at "now": every Odoo change made
while the adapter was down is **permanently skipped**; multiple replicas would double-forward.
**Fix:** persist the per-tenant cursor (in the H4 store once it moves to Postgres); on missing cursor,
seed from the last persisted value, not `initialCursor()`.

### M4 — Outbox claim is not transactional (self-invocation)
`OutboxProcessor.processOutbox` calls `claimEvents()`/`markProcessed()`/`handleFailure()` on `this`, so
`@Transactional` proxies never engage: the SELECT … FOR UPDATE SKIP LOCKED and the status flip to
PROCESSING run in separate implicit transactions → two instances can claim the same event. ERP-side
idempotency (H4 caveat) is the only guard. Same pattern in DriverService/AppBackend processors.
**Fix:** move claim into a separate bean or use `TransactionTemplate`; single atomic
`UPDATE … SET status='PROCESSING' … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING *`.

### M5 — PublicTenantResolver: unauthenticated fan-out scan + unbounded cache
Every unknown deliveryId triggers one query **per tenant schema** with no rate limit (public endpoint →
DoS amplification, grows linearly with tenant count), and the positive cache is unbounded (delivery-count
memory growth, never evicted). **Fix:** Caffeine cache with max size + TTL; negative caching (60s);
rate-limit `/api/v1/public/track/**` at the gateway.

### M6 — No tenant/correlation context in logs (observability regression)
No MDC anywhere; log lines cannot be attributed to a tenant, which is why the reported regressions were
so hard to localize. **Fix:** servlet filter + AMQP post-processor + task decorator put
`companyId`, `requestId` (UUID or gateway-generated), `userId` into MDC; add them to the logback
pattern in all 5 services. Pairs with M1's decorator.

### M7 — `/internal/**` reachable through the public gateway
Routes `internal-app-backend`, `internal-app-backend-admin-users`, `internal-driver-service`
(ApiGateway `application.yml` 220–235) expose service-to-service endpoints to the internet, protected
only by the SERVICE-role JWT check in each service. **Fix:** remove these routes (services call each
other directly) or restrict at the gateway to the internal network / mTLS.

### M8 — Gateway picks an arbitrary organization for multi-org users
`UserContextHeaderFilter.extractCompanyId` (137–149) takes `orgs.values().iterator().next()` — a user
belonging to two orgs gets a **nondeterministic tenant** per token refresh. The "1 admin = 1 company"
rule exists only as a convention. **Fix:** if the map has >1 entry, reject (403 "ambiguous tenant") —
consistent with fail-closed; also validate the claim parses as UUID at the gateway (services currently
400 later).

---

## LOW

- **L1** Single Firebase project for all tenants (`FcmConfig`): acceptable while all tenants share one
  driver app; document it, and gate any future per-tenant app requirement on a per-tenant
  `FirebaseApp` registry keyed by companyId.
- **L2** `SettingsClient`/`TenantErpProviderResolver` `"__no_tenant__"`/`FALLBACK="odoo"` paths: a
  tenant-less caller silently gets the default provider — prefer throwing (ties into H1).
- **L3** `TenantIterator` swallows per-tenant job failures with a log only — add a metric so a tenant
  that fails every tick for a week is visible.
- **L4** `EventPublisher.getDriverName` does a Feign call per event build (N+1 on bulk operations) —
  batch or cache per-request.
- **L5** `TenantErpProviderResolver` falls back to `"odoo"` for unconfigured tenants — a tenant with no
  ERP intentionally configured will attempt Odoo calls that fail; `"none"` is the safer default now that
  `ErpProviderRouter` handles it cleanly.

---

## What is correct (verified, keep as-is)
- Hibernate `MultiTenantConnectionProvider` / `CurrentTenantIdentifierResolver` pattern (×3 services):
  search_path set on the actual borrowed connection, reset on release, single pool.
- Gateway header sanitization (strips inbound `X-User-*`/`X-Company-Id`) + fail-closed 403 on missing
  org claim.
- `TenantContextFilter` clear-in-finally; `TenantInboundPostProcessor` clear-then-set wired on all four
  Rabbit container factories; `TenantMessagePostProcessor` stamping on publish.
- `TenantIterator` catalog-driven enumeration (`company_%` schemas) for scheduled jobs.
- Feign `X-Company-Id` propagation incl. the TenantContext fallback for non-HTTP threads.
- `SettingsClient` per-tenant settings cache with CONNECTED gating; `OdooMetadataCache`/
  `CapabilityCache`/`ErpLookupService` cache keys are tenant-prefixed (C2 is about the fetch thread,
  not the key). `ErpMapping` carries `tenant_id`.
- `PublicTrackingTenantFilter` prefix matches the controllers (`/api/v1/public/track/**`).
- MinIO object keys are tenant-prefixed when context exists; onboarding rollback logic.

## Recommended execution order
1. **C1, C2** (ERP correctness) — small, surgical, immediately de-flakes Odoo sync.
2. **C3, H5** (cross-tenant leaks) — tenant-scope health snapshot; company-scope driver topics.
3. **C4 + M1 + M6** together — remove WS ThreadLocal writes, centralize async tenant propagation,
   add MDC. This shrinks the entire "context loss" bug class.
4. **H1 + M2** — flip fail-open to fail-closed (web layer + topic fallback), with the public allowlist.
5. **H2** — FCM observability + KC-membership/token-backfill verification, then E2E test.
6. **H3, H4, M3, M4, M5, M7, M8** — storage privacy, adapter persistence, cursor durability, outbox
   atomicity, hardening.
7. **CI:** generalize `TenantIsolationIT` into a two-tenant matrix (API, WS, files, FCM lookup, ERP
   settings) and add the ArchUnit guards (no bare runAsync; no TenantContext.set outside sanctioned
   entry points).
