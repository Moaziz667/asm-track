# ASM Track — Full Implementation Plan

## Part 1: Bug Fixes (Completed)

### 1.1 PeriodResolver Aliases
- **File:** `Microservices/DeliveryMicroservice/src/main/java/com/asm/delivery/service/analytics/filter/PeriodResolver.java`
- **Change:** Added `day→today`, `week→wtd`, `month→mtd` aliases in the switch block
- **Status:** Done

### 1.2 RMA Returnable Quantity
- **Files:**
  - `repository/RmaRepository.java` — new `sumReturnedQtyBySku()` query (counts only RECEIVED/RESTOCKED)
  - `service/RmaService.java` — subtract returned qty from delivered qty in `create()`
- **Status:** Done

### 1.3 RMA unitPrice in CreateReturnModal
- **File:** `Apps/admin-app-react/src/components/returns/CreateReturnModal.tsx`
- **Change:** Added `unitPrice` to submit payload, mapped from delivery items
- **Status:** Done

### 1.4 Guard "Nouveau retour" Button
- **File:** `Apps/admin-app-react/src/pages/deliveries/DeliveryDetailsPage.tsx:235`
- **Change:** Added `!delivery.hasOpenReturn` check
- **Also:** `types/index.ts:131` — added `hasOpenReturn?: boolean` to Delivery type
- **Status:** Done

### 1.5 Sidebar Badge Fix
- **File:** `Apps/admin-app-react/src/components/Sidebar.tsx:143`
- **Change:** `period: 'all'` → `period: 'day'` + filtered out `SCHEDULED_MONITORING` motif
- **Status:** Done

### 1.6 ERP Operation Code Deduplication
- **New file:** `service/ErpNotificationLabel.java` — shared helper for ERP notification labels
- **Modified:** `OutboxProcessor.java` and `ErpSyncResultConsumer.java` — both use `ErpNotificationLabel.of()`
- **Status:** Done

### 1.7 Deduplicate OPEN_STATUSES
- **File:** `service/dispatch/DispatchService.java:880-884`
- **Change:** `hasOpenReturn()` now uses `RmaService.OPEN_STATUSES` (made `public static final`)
- **Status:** Done

### 1.8 @Min(1) Validation
- **File:** `dto/request/CreateRmaRequest.java`
- **Change:** Added `@Min(1)` on `Item.quantity`
- **Status:** Done

### 1.9 Dead Code Cleanup
- Extracted `ErpNotificationLabel.java` as single source of truth
- Removed duplicate `erpOperationCode()` from `OutboxProcessor` and `ErpSyncResultConsumer`
- **Status:** Done

### 1.10 RMA Tests (33 new tests)
- `RmaServiceTest.java` — 18 tests (create, transition, resync)
- `AdminRmaControllerTest.java` — 6 tests (delegation)
- `ErpNotificationLabelTest.java` — 9 tests (event types, op codes, edge cases)
- **Total:** 115 tests, 0 new failures
- **Status:** Done

---

## Part 2: Public RMA on Tracking Page ✅ DONE (2026-07-12)

> **Implémenté + vérifié E2E.** Écarts vs plan initial : réutilise `RmaService.create/transition` (toute la
> logique métier — clamp, idempotence, garde note — pas de duplication) ; `findOpenByDeliveryId` n'existait
> pas → filtre sur `OPEN_STATUSES` ; `sumReturnedQtyBySku` renvoie `List<Object[]>` (pas Map) ; 429 via
> `AppException.tooManyRequests` (ResponseStatusException devenait 500 dans le GlobalExceptionHandler) ;
> acteur CLIENT = `UserPrincipal` synthétique (pas d'overload logAction). Tests gradle SUCCESSFUL.

> **Corrections vérifiées contre le code (2026-07-12).** Le plan initial contenait des erreurs corrigées ci-dessous :
> - `deliveredQuantitiesBySku` est **`private`** et prend un **`Order`** (pas `Delivery`) → la rendre package-private et passer `delivery.getOrder()`.
> - L'enum condition est **`RmaItemCondition`** (RESELLABLE/DAMAGED), pas `ReturnCondition`. `CreateRmaRequest.Item` porte déjà `sku/name/quantity/unitPrice(BigDecimal)/condition/reason` → **réutiliser `CreateRmaRequest`** au lieu d'un DTO custom.
> - **Photos RMA : aucun modèle n'existe.** Décision : on inclut les photos → nouvelle table `rma_photo` + entité + migration **V24** (voir §2.0). Sans ça, `photoKeys` n'a nulle part où persister.
> - Sécurité : **OK** — `/api/public/**` est déjà `permitAll` (SecurityConfig:30). Le préfixe `/api/public/track/**` est correct, **aucune modif SecurityConfig requise**.
> - Rate limiter : renvoyer **429** (`ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)`), pas 400 ; purger les **clés** vides (pas seulement les timestamps).
> - Migrations : dernière = **V23**. Photos = **V24** ; l'audit-trail RMA (Part 3 §3.9) devient **V25** (pas V30).
> - Fichiers i18n = **`src/lib/i18n/{ux,en,ar}-copy.ts`** (le dossier `src/locales/` n'existe pas).
> - **Audit acteur (enterprise) :** `logAction(null,…)` est null-safe mais loggue **"SYSTEM"** → trompeur pour une action **client**. Les create/cancel publics doivent tracer un acteur **CLIENT** (overload `logAction`/`create`/`transition` avec actorName/actorRole explicites, ou `UserPrincipal` synthétique role="CLIENT"). Garde note obligatoire CANCELLED = **satisfaite** (le plan passe une note). Les deux vérifiés dans le code.

### Overview
Add client-facing RMA request capability to `/track/:deliveryId`. The client can:
- Request a return (select items, condition, reason, photos)
- Cancel a pending return (when REQUESTED)
- Track the return lifecycle (status + timeline)
- See rejection reasons

### Architecture

```
/track/:deliveryId (public, no auth)
  ├── Delivery status + map + driver info (existing)
  ├── Order items list (existing)
  ├── Return status banner (existing, enhanced)
  └── ReturnSection (NEW)
       ├── If no return: "Demander un retour" button
       │    └── ReturnRequestForm
       │         ├── Item checkboxes (qty bounded)
       │         ├── Condition radio (RESELLABLE / DAMAGED)
       │         ├── Reason textarea
       │         ├── Photo upload (eager, multipart → MinIO)
       │         └── Submit → creates RMA (REQUESTED)
       ├── If REQUESTED: status + "Annuler" button
       ├── If REJECTED: status + resolution note
       └── If other status: status + timeline
```

### Backend Changes

#### 2.0 Photo model — `RmaPhoto` entity + migration V24 (NEW)

RMA has **no** photo storage today. Client-uploaded return photos need a home. Child table (avoids ORM
array-type friction; mirrors the clean relational shape used elsewhere).

**Migration `V24__rma_client_photos.sql`:**
```sql
CREATE TABLE rma_photo (
    id          UUID PRIMARY KEY,
    rma_id      UUID NOT NULL REFERENCES rma(id) ON DELETE CASCADE,
    url         TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_rma_photo_rma ON rma_photo(rma_id);
```

**`RmaPhoto.java`** — `id`, `rmaId`, `url`, `createdAt`. **`RmaPhotoRepository`** — `findByRmaId(UUID)`.
- `RmaResponse` (+ admin `RmaDetailDrawer`) gains `photoUrls: List<String>` so staff can view the evidence.
- Photos are uploaded eagerly (§2.3) → returns **MinIO object URLs** → the create payload carries those URLs
  → `createReturn` inserts one `rma_photo` row per URL, linked to the new RMA. (Rename the field `photoKeys`
  → `photoUrls` for accuracy — the upload endpoint returns full URLs, not keys.)

#### 2.1 Rate Limiter — `PublicReturnRateLimiter.java` (NEW)

In-memory per-IP rate limiter using `ConcurrentHashMap<String, List<Instant>>`.

- Key: `ip:deliveryId`
- Limit: 4 requests per 24h per key
- Cleanup: prune old timestamps AND remove the map **key** when its list empties (else the map grows
  unbounded across distinct ip:deliveryId).
- On exceed: throw `ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, msg)` → **HTTP 429** (matches the
  `rateLimitExceeded` i18n key; do NOT use `AppException.badRequest` which returns 400).

```java
@Component
public class PublicReturnRateLimiter {
    private static final int MAX_REQUESTS = 4;
    private static final Duration WINDOW = Duration.ofHours(24);
    private final ConcurrentHashMap<String, List<Instant>> hits = new ConcurrentHashMap<>();

    public boolean tryAcquire(String ip, UUID deliveryId) {
        String key = ip + ":" + deliveryId;
        Instant cutoff = Instant.now().minus(WINDOW);
        List<Instant> timestamps = hits.compute(key, (k, existing) -> {
            List<Instant> list = existing == null ? new CopyOnWriteArrayList<>() : existing;
            list.removeIf(t -> t.isBefore(cutoff));
            return list;
        });
        if (timestamps.size() >= MAX_REQUESTS) return false;
        timestamps.add(Instant.now());
        return true;
    }

    // Periodically (or lazily) drop empty keys so the map doesn't leak:
    //   hits.entrySet().removeIf(e -> e.getValue().isEmpty());
}
```

#### 2.2 DTOs (NEW)

**`PublicReturnRequest.java`:** — thin wrapper; the item shape already exists on `CreateRmaRequest.Item`
(`sku/name/quantity/unitPrice/condition:RmaItemCondition/reason`). Prefer **reusing `CreateRmaRequest.Item`**
rather than re-declaring. Note: the enum is **`RmaItemCondition`** (RESELLABLE/DAMAGED), NOT `ReturnCondition`.
```java
@Data
public class PublicReturnRequest {
    @NotEmpty
    private List<CreateRmaRequest.Item> items; // reuse existing item shape

    private List<String> photoUrls; // MinIO URLs from the eager photo upload (§2.3)
}
```
`deliveryId` comes from the path, not the body → the service builds a `CreateRmaRequest` (set `deliveryId`
from path) before delegating to `rmaService.create(...)`.

**`PublicReturnableItemsResponse.java`:**
```java
@Data @Builder
public class PublicReturnableItemsResponse {
    private String deliveryStatus;
    private boolean hasOpenReturn;
    private UUID openReturnId;
    private List<ReturnableItem> items;

    @Data @Builder
    public static class ReturnableItem {
        private String sku;
        private String name;
        private Integer deliveredQty;
        private Integer returnableQty;
        private Double unitPrice;
    }
}
```

#### 2.3 Photo Upload — `RmaPhotoStorageService.java` (NEW)

Multipart upload to MinIO, matching company logo pattern.

- Path: `rma/{deliveryId}/client/{uuid}.jpg`
- Validates: JPEG/PNG only, 5MB max per file, 5 files max
- Uses existing `MinioStorageService.uploadFile(byte[], contentType, path)`
- Returns public URL

```java
@Service
@RequiredArgsConstructor
public class RmaPhotoStorageService {
    private final MinioStorageService minioStorageService;

    public List<String> uploadPhotos(UUID deliveryId, List<MultipartFile> files) {
        // validate count, size, content type
        // upload each to rma/{deliveryId}/client/{uuid}.jpg
        // return list of public URLs
    }
}
```

#### 2.4 Public RMA Controller — `PublicRmaController.java` (NEW)

```java
@RestController
@RequestMapping("/api/public/track/{deliveryId}/return")
@RequiredArgsConstructor
public class PublicRmaController {

    private final PublicRmaService service;
    private final PublicReturnRateLimiter rateLimiter;

    @GetMapping
    public ResponseEntity<PublicReturnableItemsResponse> getReturnableItems(
            @PathVariable UUID deliveryId) {
        return ResponseEntity.ok(service.getReturnableItems(deliveryId));
    }

    @PostMapping
    public ResponseEntity<TrackingResponse> createReturn(
            @PathVariable UUID deliveryId,
            @RequestBody @Valid PublicReturnRequest request,
            HttpServletRequest httpRequest) {
        String ip = getClientIp(httpRequest);
        if (!rateLimiter.tryAcquire(ip, deliveryId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trop de demandes. Réessayez demain.");
        }
        return ResponseEntity.ok(service.createReturn(deliveryId, request));
    }

    @PostMapping("/cancel")
    public ResponseEntity<TrackingResponse> cancelReturn(
            @PathVariable UUID deliveryId) {
        return ResponseEntity.ok(service.cancelReturn(deliveryId));
    }

    @PostMapping("/photos")
    public ResponseEntity<List<String>> uploadPhotos(
            @PathVariable UUID deliveryId,
            @RequestParam("files") List<MultipartFile> files) {
        return ResponseEntity.ok(service.uploadPhotos(deliveryId, files));
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}
```

#### 2.5 Public RMA Service — `PublicRmaService.java` (NEW)

```java
@Service
@RequiredArgsConstructor
public class PublicRmaService {

    private final DeliveryRepository deliveryRepo;
    private final RmaRepository rmaRepo;
    private final RmaService rmaService;
    private final RmaPhotoStorageService photoService;
    private final OrderRepository orderRepo;

    @Transactional(readOnly = true)
    public PublicReturnableItemsResponse getReturnableItems(UUID deliveryId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
            .orElseThrow(() -> AppException.notFound("Delivery not found"));

        // check open return
        Optional<Rma> openReturn = rmaRepo.findOpenByDeliveryId(deliveryId);

        // compute returnable items — deliveredQuantitiesBySku is currently PRIVATE and takes an Order.
        // Make it package-private (or add a public helper) and pass delivery.getOrder().
        Map<String, Integer> delivered = rmaService.deliveredQuantitiesBySku(delivery.getOrder());
        Map<String, Integer> returned = rmaRepo.sumReturnedQtyBySku(deliveryId); // use the single field name `rmaRepo`

        List<ReturnableItem> items = delivered.entrySet().stream()
            .map(e -> {
                String sku = e.getKey();
                int deliveredQty = e.getValue();
                int returnedQty = returned.getOrDefault(sku, 0);
                int returnable = deliveredQty - returnedQty;
                // find item name + unitPrice from order
                return ReturnableItem.builder()
                    .sku(sku)
                    .name(itemName)
                    .deliveredQty(deliveredQty)
                    .returnableQty(returnable)
                    .unitPrice(unitPrice)
                    .build();
            })
            .filter(i -> i.getReturnableQty() > 0)
            .collect(Collectors.toList());

        return PublicReturnableItemsResponse.builder()
            .deliveryStatus(delivery.getStatus().name())
            .hasOpenReturn(openReturn.isPresent())
            .openReturnId(openReturn.map(Rma::getId).orElse(null))
            .items(items)
            .build();
    }

    @Transactional
    public TrackingResponse createReturn(UUID deliveryId, PublicReturnRequest request) {
        // rate limit already checked by controller.
        // 1. Build a CreateRmaRequest: deliveryId from PATH + request.items; delegate to
        //    rmaService.create(req, <CLIENT principal>).
        //    VERIFIED: auditLogService.logAction(null,...) is null-SAFE (defaults actor to "SYSTEM").
        //    ENTERPRISE-GRADE: do NOT pass null — a public return logged as "SYSTEM" is misleading. Pass a
        //    synthetic CLIENT actor so the audit trail reads "CLIENT" (public self-service), not SYSTEM.
        //    Two clean options:
        //      (a) add AuditLogService.logPublicAction(actorLabel="CLIENT", ...) + a create()/transition()
        //          overload that accepts an explicit actorName/actorRole; or
        //      (b) construct a lightweight UserPrincipal{ role="CLIENT", displayName="Client (suivi public)" }.
        //    create() already null-guards createdBy; set it to the same CLIENT label.
        //    create() also throws on an existing OPEN return → surfaces as the duplicate-return guard.
        // 2. For each url in request.photoUrls: insert an rma_photo row (rmaId = created RMA id).
        // 3. return publicTrackingService.getTracking(deliveryId);
    }

    @Transactional
    public TrackingResponse cancelReturn(UUID deliveryId) {
        Rma rma = rmaRepo.findOpenByDeliveryId(deliveryId)
            .orElseThrow(() -> AppException.notFound("No open return found"));
        if (rma.getStatus() != RmaStatus.REQUESTED) {
            throw AppException.badRequest("Cannot cancel return in status: " + rma.getStatus());
        }
        // Note is mandatory for CANCELLED (RmaService guard) — "Annulé par le client" satisfies it. VERIFIED.
        // Pass the same synthetic CLIENT actor (not null) so the trail reads CLIENT, not SYSTEM.
        rmaService.transition(rma.getId(), RmaStatus.CANCELLED, "Annulé par le client", clientPrincipal);
        return publicTrackingService.getTracking(deliveryId);
    }

    @Transactional
    public List<String> uploadPhotos(UUID deliveryId, List<MultipartFile> files) {
        return photoService.uploadPhotos(deliveryId, files);
    }
}
```

#### 2.6 TrackingResponse Changes

**`TrackingResponse.java`** — add field:
```java
private String returnResolutionNote;
```

**`PublicTrackingService.java`** — in `getTracking()`, after computing `returnStatus`:
```java
String returnResolutionNote = null;
Optional<Rma> latestRma = rmaRepo.findByDeliveryIdOrderByCreatedAtDesc(deliveryId).stream().findFirst();
if (latestRma.isPresent()) {
    returnStatus = latestRma.get().getStatus().name();
    if (latestRma.get().getStatus() == RmaStatus.REJECTED) {
        returnResolutionNote = latestRma.get().getResolutionNote();
    }
}
```

### Frontend Changes

#### 2.7 New Component — `ReturnSection.tsx` (NEW)

**Path:** `Apps/admin-app-react/src/pages/track-delivery/ReturnSection.tsx`

**Props:** `{ deliveryId: string; deliveryStatus: string; returnStatus?: string; returnResolutionNote?: string; onReturnCreated?: () => void }`

**Behavior:**
1. Fetches returnable items from `GET /api/public/track/{deliveryId}/return`
2. If `hasOpenReturn`:
   - If REQUESTED: show status + "Annuler" button
   - If REJECTED: show status + resolution note
   - Otherwise: show status
3. If no open return and delivery is DELIVERED/PARTIALLY_DELIVERED/FAILED:
   - Show "Demander un retour" button
   - Click opens inline form (no modal, bottom sheet style)
   - Form: item checkboxes, condition radio, reason textarea, photo upload
   - Submit calls `POST /api/public/track/{deliveryId}/return`
4. Photo upload: eager, multipart to `/api/public/track/{deliveryId}/return/photos`
   - File input: `accept="image/jpeg,image/png"`, multiple
   - Max 5 files, 5MB each
   - Shows thumbnail previews after upload
   - Returned MinIO URLs (`photoUrls`) are included in the create request body

#### 2.8 Modified — `TrackDeliveryPage.tsx`

Add `ReturnSection` below the order items section (after line ~245):
```tsx
<ReturnSection
  deliveryId={deliveryId!}
  deliveryStatus={data.status}
  returnStatus={data.returnStatus}
  returnResolutionNote={data.returnResolutionNote}
  onReturnCreated={() => refetch()}
/>
```

#### 2.9 Translation Keys

Add to `trackingPage` in all 3 locale files:

| Key | FR | EN | AR |
|---|---|---|---|
| `requestReturnBtn` | Demander un retour | Request a return | طلب إرجاع |
| `returnFormTitle` | Formulaire de retour | Return form | نموذج الإرجاع |
| `returnFormCondition` | État de l'article | Item condition | حالة المنتج |
| `conditionResellable` | Revendable | Resellable | قابل لإعادة البيع |
| `conditionDamaged` | Endommagé | Damaged | تالف |
| `returnFormReason` | Raison du retour | Reason for return | سبب الإرجاع |
| `returnFormPhotos` | Photos (optionnel) | Photos (optional) | صور (اختياري) |
| `returnFormSubmit` | Envoyer la demande | Submit request | إرسال الطلب |
| `returnFormCancel` | Annuler la demande | Cancel request | إلغاء الطلب |
| `returnCancelledByClient` | Annulé par le client | Cancelled by client | ملغي من قبل العميل |
| `returnResolutionNote` | Motif de la décision | Resolution reason | سبب القرار |
| `returnTimeline` | Chronologie | Timeline | الجدول الزمني |
| `rateLimitExceeded` | Trop de demandes. Réessayez demain. | Too many requests. Try again tomorrow. |طلبات كثيرة. حاول غداً. |

### Tests

#### 2.10 `PublicRmaControllerTest.java` (NEW, 6-8 tests)

- `getReturnableItems_returnsItems` — happy path
- `getReturnableItems_deliveryNotFound_throws404`
- `createReturn_happyPath` — creates RMA, returns tracking
- `createReturn_rateLimited_throws429`
- `createReturn_duplicateOpenReturn_throws400`
- `cancelReturn_happyPath` — cancels REQUESTED return
- `cancelReturn_wrongStatus_throws400`
- `uploadPhotos_happyPath` — uploads files, returns URLs

#### 2.11 `PublicRmaServiceTest.java` (NEW, 8-10 tests)

- `getReturnableItems_computesReturnableQty`
- `getReturnableItems_withOpenReturn_setsFlag`
- `getReturnableItems_allReturned_showsEmpty`
- `createReturn_delegatesToRmaService`
- `createReturn_linksPhotos`
- `cancelReturn_requestsTransition`
- `cancelReturn_notRequested_throws`
- `uploadPhotos_validatesAndStores`

#### 2.12 `PublicReturnRateLimiterTest.java` (NEW, 3-4 tests)

- `tryAcquire_underLimit_allows`
- `tryAcquire_atLimit_blocks`
- `tryAcquire_afterWindow_allowsAgain`
- `differentIps_areTrackedSeparately`

### Files Summary

| File | Action |
|---|---|
| `Microservices/.../controller/PublicRmaController.java` | **NEW** |
| `Microservices/.../service/PublicRmaService.java` | **NEW** |
| `Microservices/.../service/PublicReturnRateLimiter.java` | **NEW** |
| `Microservices/.../storage/RmaPhotoStorageService.java` | **NEW** |
| `Microservices/.../entity/RmaPhoto.java` | **NEW** |
| `Microservices/.../repository/RmaPhotoRepository.java` | **NEW** |
| `Microservices/.../resources/db/migration/V24__rma_client_photos.sql` | **NEW** |
| `Microservices/.../dto/request/PublicReturnRequest.java` | **NEW** |
| `Microservices/.../dto/response/PublicReturnableItemsResponse.java` | **NEW** |
| `Microservices/.../dto/response/RmaResponse.java` (add `photoUrls`) | MODIFY |
| `Microservices/.../dto/response/TrackingResponse.java` | MODIFY |
| `Microservices/.../service/PublicTrackingService.java` | MODIFY |
| `Microservices/.../service/RmaService.java` (make `deliveredQuantitiesBySku` non-private; CLIENT-actor create/transition overload) | MODIFY |
| `Microservices/.../service/AuditLogService.java` (explicit-actor overload for public/CLIENT actions) | MODIFY |
| `Microservices/.../test/.../PublicRmaControllerTest.java` | **NEW** |
| `Microservices/.../test/.../PublicRmaServiceTest.java` | **NEW** |
| `Microservices/.../test/.../PublicReturnRateLimiterTest.java` | **NEW** |
| `Apps/admin-app-react/src/pages/track-delivery/ReturnSection.tsx` | **NEW** |
| `Apps/admin-app-react/src/pages/track-delivery/TrackDeliveryPage.tsx` | MODIFY |
| `Apps/admin-app-react/src/lib/i18n/ux-copy.ts` | MODIFY |
| `Apps/admin-app-react/src/lib/i18n/en-copy.ts` | MODIFY |
| `Apps/admin-app-react/src/lib/i18n/ar-copy.ts` | MODIFY |

### Implementation Order

1. Rate limiter + DTOs
2. `RmaPhotoStorageService` (MinIO upload)
3. `PublicRmaService`
4. `PublicRmaController`
5. `TrackingResponse` + `PublicTrackingService` updates
6. Backend tests
7. Translations (FR/EN/AR)
8. `ReturnSection.tsx` + `TrackDeliveryPage.tsx`
9. Docker rebuild + `npm run typecheck`

---

## Part 3: Enterprise RMA Features

> **Phases 3-5 (P1) DONE (2026-07-12).** Audit-trail (`rma_status_history` V25 + endpoint + real drawer timeline),
> date-range filter, real-time (`return.status_changed` on admin.deliveries). Écarts : historique acteur en
> strings (name+role, accueille CLIENT) ; migration V25 (pas V30) ; realtime réutilise le topic existant
> (pas de `/topic/admin.returns`). Restant : Phase 1 (quick wins P3), Phase 2 (React Query P3), Phase 6
> (inline actions + shipping P2).

### Phase 1 — Quick Wins (no backend changes)

#### 3.1 Move Return Types to Shared File (#11)
- **File:** `Apps/admin-app-react/src/types/index.ts`
- **Change:** Extract `Return`, `ReturnItem`, `ReturnStatus` interfaces from `ReturnsPage.tsx` into shared types
- **Also:** Update imports in `ReturnsPage.tsx`, `RmaDetailDrawer.tsx`, `CreateReturnModal.tsx`
- **Priority:** P3

#### 3.2 Delete Dead `sumReturnedUnits` Query (#14)
- **File:** `Microservices/.../repository/RmaRepository.java`
- **Change:** Remove unused `sumReturnedUnits` method (replaced by `sumReturnedQtyBySku`)
- **Priority:** P3

#### 3.3 Skeleton Loading in RMA Detail Drawer (#13)
- **File:** `Apps/admin-app-react/src/components/returns/RmaDetailDrawer.tsx`
- **Change:** Add skeleton placeholder while drawer data loads (reuse existing skeleton pattern from deliveries)
- **Priority:** P3

#### 3.4 Return Value KPI Metric (#16)
- **File:** `Apps/admin-app-react/src/pages/returns/ReturnsPage.tsx`
- **Change:** Add total return value (sum of `unitPrice × quantity`) to KPI bar alongside existing counts
- **Backend:** `AdminRmaController` — add `totalValue` field to KPI response
- **Priority:** P3

---

### Phase 2 — React Query Migration

#### 3.5 Create `useReturns.ts` Hook (#12)
- **New file:** `Apps/admin-app-react/src/hooks/useReturns.ts`
- **Pattern:** Match existing `useDeliveries.ts` (query key tuples, `staleTime: 30000`, cache invalidation)
- **Exports:** `useReturns(filter)`, `useReturn(id)`, `useCreateReturn()`, `useTransitionReturn()`, `useResyncReturn()`
- **Priority:** P3

#### 3.6 Optimistic Updates (#15)
- **File:** `Apps/admin-app-react/src/hooks/useReturns.ts`
- **Change:** `useTransitionReturn()` and `useResyncReturn()` use `onMutate` to snapshot cache, rollback on error, invalidate on success
- **Priority:** P3

#### 3.7 Refactor Pages to Use Hook
- **Files:** `ReturnsPage.tsx`, `RmaDetailDrawer.tsx`, `CreateReturnModal.tsx`
- **Change:** Replace manual `useState` + `useEffect` + `api.get` with `useReturns()` / `useCreateReturn()` etc.
- **Priority:** P3

---

### Phase 3 — Audit Trail

#### 3.8 New Entity: `RmaStatusHistory`
- **New file:** `Microservices/.../entity/RmaStatusHistory.java`
- **Fields:** `id`, `rmaId`, `fromStatus`, `toStatus`, `note`, `actedBy` (nullable for public), `actedByName` (nullable), `createdAt`
- **Pattern:** Mirror `DeliveryStatusHistory` entity
- **Priority:** P1

#### 3.9 Repository + Migration
- **New file:** `Microservices/.../repository/RmaStatusHistoryRepository.java`
- **Query:** `findByRmaIdOrderByCreatedAtAsc(UUID rmaId)`
- **Migration:** `V25__create_rma_status_history.sql` — new table (V24 is taken by RMA photos; renumber to the real next version at implementation time)
- **Priority:** P1

#### 3.10 Record Transitions in `RmaService`
- **File:** `Microservices/.../service/RmaService.java`
- **Change:** In `create()` and `transition()`, insert a `RmaStatusHistory` row after each status change
- **Priority:** P1

#### 3.11 New Endpoint: `GET /api/admin/returns/{id}/history`
- **File:** `Microservices/.../controller/AdminRmaController.java`
- **Change:** Returns `List<RmaStatusHistoryDto>` for the timeline
- **Priority:** P1

#### 3.12 Replace Synthetic Timeline in Drawer
- **File:** `Apps/admin-app-react/src/components/returns/RmaDetailDrawer.tsx`
- **Change:** Replace the hardcoded timeline steps with real history from the new endpoint
- **Priority:** P1

---

### Phase 4 — Date Range Filter

#### 3.13 Backend: Date Range Query Param
- **File:** `Microservices/.../service/RmaService.java` — `list()` method
- **Change:** Add optional `dateFrom` and `dateTo` parameters, filter by `createdAt` range
- **File:** `Microservices/.../controller/AdminRmaController.java`
- **Change:** Add `@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo`
- **Priority:** P1

#### 3.14 Frontend: Date Range Pickers
- **File:** `Apps/admin-app-react/src/pages/returns/ReturnsPage.tsx`
- **Change:** Add two `DatePickerPopover` components (from/to) in the filter bar, matching `AuditLogsPage` pattern
- **Priority:** P1

---

### Phase 5 — Real-Time Updates

#### 3.15 Backend: Publish RMA Status Changes
- **File:** `Microservices/.../service/RmaService.java`
- **Change:** After `transition()`, call `eventPublisher.publishRmaStatusChanged(rma)` (new method on existing `EventPublisher`)
- **New topic:** `/topic/admin.returns` (or reuse `/topic.admin.deliveries` with RMA event type)
- **Priority:** P1

#### 3.16 Frontend: Subscribe to RMA Events
- **File:** `Apps/admin-app-react/src/hooks/useReturns.ts` (or `useReturnsData`)
- **Change:** `useRealtimeEvent(['RETURN_STATUS_CHANGED'], handler)` — invalidates return list/detail cache
- **Priority:** P1

---

### Phase 6 — Inline Actions + Shipping Tracking

#### 3.17 Inline Quick-Actions (#10)
- **File:** `Apps/admin-app-react/src/pages/returns/ReturnsPage.tsx`
- **Change:** Add action buttons directly in table rows (Approve, Reject, Mark Received) instead of only in the detail drawer
- **Priority:** P2

#### 3.18 Shipping Tracking Fields (#9)
- **New fields on `Rma` entity:** `trackingNumber`, `shippingCarrier`, `shippedAt`, `receivedAt`
- **New fields on `RmaItem`:** `receivedQty` (for partial receives)
- **UI:** Add shipping info section in `RmaDetailDrawer`
- **Priority:** P2

---

### Phase Summary

| Phase | Scope | Backend | Frontend | Priority |
|---|---|---|---|---|
| **1** | Quick wins: types, dead code, skeleton, KPI | Minor | 4 files | P3 |
| **2** | React Query migration + optimistic updates | None | 4 files | P3 |
| **3** | Audit trail: entity + migration + endpoint + drawer | 5 new/modified | 1 modified | P1 |
| **4** | Date range filter | 2 modified | 1 modified | P1 |
| **5** | Real-time RMA status | 2 modified | 1 modified | P1 |
| **6** | Inline actions + shipping tracking | 2 new fields | 2 modified | P2 |

### Implementation Order

**Completed:**
1. ~~Bug Fixes (Part 1)~~
2. ~~Public RMA (Part 2)~~

**Next:**
3. Phase 3 — Audit Trail (P1, most value)
4. Phase 4 — Date Range Filter (P1, quick)
5. Phase 5 — Real-Time (P1, completes audit trail)
6. Phase 1 — Quick Wins (P3, low effort)
7. Phase 2 — React Query (P3, refactoring)
8. Phase 6 — Inline Actions + Shipping (P2, polish)

---

### Docker Rebuild Command
```powershell
cd Microservices; docker compose build delivery-service; if ($?) { docker compose up -d delivery-service }
```

### Typecheck Command
```powershell
cd Apps/admin-app-react; npm run typecheck
```
