package com.asm.delivery.service;

import com.asm.delivery.dto.request.ProofOfDeliveryRequest;
import com.asm.delivery.dto.request.IncidentReportRequest;
import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.dto.response.HandoffTokenResponse;
import com.asm.delivery.dto.response.ProofOfDeliveryResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.repository.*;
import com.asm.delivery.storage.MinioStorageService;
import java.time.LocalDate;
import com.asm.delivery.storage.StorageException;
import com.asm.delivery.messaging.DriverCommandPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverDeliveryService {

    private final DriverDeliveryMapper            mapper;
    private final DeliveryTransitionSupport       transitions;
    private final DriverIncidentService           incidentService;
    private final DeliveryRepository              deliveryRepo;
    private final TrackingRepository              trackingRepo;
    private final EventPublisher                  eventPublisher;
    private final ProofOfDeliveryRepository       podRepo;
    private final DriverCommandPublisher          driverCommandPublisher;
    private final MinioStorageService             minioStorageService;
    private final com.asm.delivery.service.route.RouteExecutionService routeExecutionService;
    private final DelayCalculationService         delayCalculationService;
    private final AuditLogService                  auditLogService;
    private final RouteRepository                 routeRepository;
    private final RouteStopRepository             routeStopRepository;

    private final OutboxProcessor                outboxProcessor;
    private final com.asm.delivery.repository.OrderRepository orderRepo;
    private final CashCollectionService           cashCollectionService;
    private final FailureReasonService            failureReasonService;
    private final com.asm.delivery.sla.SlaStateService slaStateService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** Lazy — ADR-033: advances the RMA to RECEIVED when a RETURN_PICKUP leg is completed at the depot. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private RmaService rmaService;

    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
    );

    private static final List<RouteStatus> ACTIVE_ROUTE_STATUSES = List.of(
            RouteStatus.VALIDATED,
            RouteStatus.IN_PROGRESS
    );

    // ── Get active delivery for driver ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DriverDeliveryResponse> getActive(UUID driverId) {
        List<DriverDeliveryResponse> active = deliveryRepo.findActiveForDriver(driverId, ACTIVE_STATUSES).stream()
                .map(mapper::toDriverDeliveryResponse)
                .collect(Collectors.toList());

        // Also include deliveries where this driver is the handoff SENDER (package still physically with them)
        List<UUID> activeIds = active.stream()
                .map(r -> r.getDeliveryId())
                .collect(Collectors.toList());
        routeStopRepository.findPendingHandoffsByFromDriverWithRoute(driverId).stream()
                .map(stop -> deliveryRepo.findByIdWithOrder(stop.getDeliveryId()).orElse(null))
                .filter(d -> d != null && !activeIds.contains(d.getId()))
                .map(mapper::toDriverDeliveryResponse)
                .forEach(active::add);

        return active;
    }

    // ── Get a specific delivery (with driver authorization check) ─────────────

    @Transactional(readOnly = true)
    public DriverDeliveryResponse getDelivery(UUID deliveryId, UUID driverId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (delivery.getDriverId() != null && !delivery.getDriverId().equals(driverId)
                && delivery.getStatus() != DeliveryStatus.UNSCHEDULED) {
            // Allow handoff sender to still view the delivery
            boolean isSender = routeStopRepository.findPendingHandoffsByFromDriverWithRoute(driverId).stream()
                    .anyMatch(s -> s.getDeliveryId().equals(delivery.getId()));
            if (!isSender) throw AppException.forbidden("Not your delivery");
        }

        return mapper.toDriverDeliveryResponse(delivery);
    }

    @Transactional
    public DriverDeliveryResponse accept(UUID deliveryId, UUID driverId, UserPrincipal principal) {
        // Driver cannot accept standalone deliveries while assigned to an active route today.
        boolean hasActiveRoute = routeRepository.existsByDriverIdAndDateAndStatusIn(
                driverId, LocalDate.now(), ACTIVE_ROUTE_STATUSES);
        if (hasActiveRoute) {
            throw AppException.badRequest("Driver is assigned to an active route — standalone accept is not allowed");
        }

        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (delivery.getStatus() != DeliveryStatus.UNSCHEDULED) {
            throw AppException.conflict("Delivery is no longer available");
        }

        // Atomic UPDATE — 0 rows = race condition
        int updated = deliveryRepo.atomicAccept(deliveryId, driverId);
        if (updated == 0) {
            throw AppException.conflict("Delivery was just taken by another driver");
        }

        // Reload after update
        delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found after accept"));

        delivery.setWaitingSlaMinutes(delayCalculationService.calculateWaitingSlaMinutes(delivery));
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_ACCEPT", "DELIVERY", deliveryId.toString(),
                Map.of("driver", driverName, "client", clientName, "action", "DRIVER_ACCEPT"));

        transitions.appendHistory(delivery, DeliveryStatus.SCHEDULED, driverId.toString(), Role.DRIVER, "DELIVERY_SCHEDULED_BY_DRIVER", Map.of("driverId", driverId.toString()));
        eventPublisher.publishDeliveryScheduled(delivery.getOrder(), delivery, driverId);

        return mapper.toDriverDeliveryResponse(delivery);
    }
    @Transactional
    public DriverDeliveryResponse pickup(UUID deliveryId, UUID driverId, UserPrincipal principal) {
        Delivery delivery = transitions.loadAndAuthorize(deliveryId, driverId);
        transitions.assertStatus(delivery, DeliveryStatus.SCHEDULED, "pickup");

        delivery.setStatus(DeliveryStatus.PICKED_UP);
        delivery.setPickedUpAt(LocalDateTime.now());
        delivery.setAssignSlaMinutes(delayCalculationService.calculateAssignSlaMinutes(delivery));
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_PICKUP", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "action", "Ramassage du colis"));
        transitions.appendHistory(delivery, DeliveryStatus.PICKED_UP, driverId.toString(), Role.DRIVER, "DELIVERY_PICKED_UP", Map.of("driverId", driverId.toString()));
        eventPublisher.publishDeliveryPickedUp(delivery.getOrder(), delivery);

        return mapper.toDriverDeliveryResponse(delivery);
    }

    // ── Transit ───────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse transit(UUID deliveryId, UUID driverId, BigDecimal lat, BigDecimal lng, UserPrincipal principal) {
        Delivery delivery = transitions.loadAndAuthorize(deliveryId, driverId);
        transitions.assertStatus(delivery, DeliveryStatus.PICKED_UP, "start transit");

        LocalDateTime transitStartedAt = LocalDateTime.now();
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        delivery.setInTransitAt(transitStartedAt);
        delivery.setPickupSlaMinutes(delayCalculationService.calculatePickupSlaMinutes(delivery));

        delivery = deliveryRepo.save(delivery);

        BigDecimal originLat = lat;
        BigDecimal originLng = lng;
        if (originLat == null || originLng == null) {
            var latestTracking = trackingRepo.findFirstByDeliveryIdOrderByTimestampDesc(delivery.getId());
            if (latestTracking.isPresent()) {
                originLat = latestTracking.get().getLat();
                originLng = latestTracking.get().getLng();
            }
        }
        // DELETED: Blocking transportPort.getDriver() inside @Transactional. It caused connection exhaustion.
        // If we don't have tracking locally, we'll just publish without coordinates.

        String transitNote = "Driver started transit";
        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_TRANSIT", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "action", "Debut du transit"));
        transitions.appendHistory(delivery, DeliveryStatus.IN_TRANSIT, driverId.toString(), Role.DRIVER, "DELIVERY_TRANSIT_STARTED", Map.of("driverId", driverId.toString()));
        eventPublisher.publishDeliveryInTransit(
                delivery.getOrder(),
                delivery,
                originLat,
                originLng,
                null,
                null,
                null,
                null,
                null
        );

        return mapper.toDriverDeliveryResponse(delivery);
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse complete(UUID deliveryId, UUID driverId, UserPrincipal principal) {
        return complete(deliveryId, driverId, false, null, principal);
    }

    @Transactional
    public DriverDeliveryResponse complete(UUID deliveryId, UUID driverId, boolean isPartial, List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems, UserPrincipal principal) {
        Delivery delivery = transitions.loadAndAuthorize(deliveryId, driverId);
        if (delivery.getStatus() != DeliveryStatus.IN_TRANSIT
                && delivery.getStatus() != DeliveryStatus.PICKED_UP) {
            throw AppException.badRequest("Cannot complete from status " + delivery.getStatus());
        }

        // ADR-033 — A reverse-pickup leg SHARES the forward order. Its own line items live on the RMA, so
        // completing a collection must NEVER touch the order's JSONB lines (doing so would overwrite the
        // forward delivery's recorded quantities). The whole order-mutation block below is forward-only.
        boolean isReturnPickup = delivery.getKind() == com.asm.delivery.entity.DeliveryKind.RETURN_PICKUP;

        List<com.asm.delivery.dto.request.PartialDeliveryItem> normalizedPartialItems = partialItems;
        if (!isReturnPickup && isPartial && delivery.getOrder() != null && partialItems != null && !partialItems.isEmpty()) {
            normalizedPartialItems = normalizePartialItems(delivery.getOrder(), partialItems);
        }

        if (!isReturnPickup) {
            if (isPartial && delivery.getOrder() != null && normalizedPartialItems != null && !normalizedPartialItems.isEmpty()) {
                applyPartialQuantities(delivery.getOrder(), normalizedPartialItems);
            } else if (!isPartial && delivery.getOrder() != null && delivery.getOrder().getItems() != null) {
                // Full delivery — reset all items to fully delivered, clearing any stale partial data
                // from previous delivery attempts on the same order.
                for (com.asm.delivery.entity.OrderItem item : delivery.getOrder().getItems()) {
                    item.setQuantityDone(item.getQuantity() != null ? item.getQuantity() : 0);
                    item.setOutcome("DELIVERED");
                    item.setReason(null);
                    item.setComment(null);
                    item.setSegments(null);
                }
            }

            // PERSIST the line quantities. `items` is a JSONB column (@Type(JsonType.class)); mutating its
            // elements in place is NOT reliably detected by Hibernate dirty-checking, so without this the
            // quantityDone/outcome changes above silently revert to 0 on commit. Reassign the list reference
            // (forces the JSON column dirty) and save the order explicitly.
            if (delivery.getOrder() != null && delivery.getOrder().getItems() != null) {
                Order ord = delivery.getOrder();
                ord.setItems(new java.util.ArrayList<>(ord.getItems()));
                orderRepo.save(ord);
            }
        }

        // C3 — The final status is DERIVED from the line quantities now persisted on the order, not
        // from the mobile `isPartial` flag (which can disagree with what the driver actually keyed):
        //   nothing delivered  → FAILED  (a "partial" with 0 units is really a failed visit)
        //   everything delivered → DELIVERED (a "partial" that covered every line is really complete)
        //   some-but-not-all    → PARTIALLY_DELIVERED
        // A full delivery (no partial items) is always DELIVERED. This keeps ASM and Odoo from ever
        // recording an empty or already-complete "partial".
        DeliveryStatus finalStatus = (!isReturnPickup && isPartial && delivery.getOrder() != null)
                ? deriveStatusFromQuantities(delivery.getOrder())
                : DeliveryStatus.DELIVERED;
        // C3 — Nothing was actually delivered: this is a failed visit, not a "partial". Delegate to
        // fail() so the full failure path runs (failure code, driver release, ERP_SYNC_FAILURE) instead
        // of pushing an empty partial picking to Odoo. The failure CODE is derived from the lines
        // (REFUSED when the customer rejected goods, else OTHER) — no debug text in the comment, so the
        // notification reads cleanly ("Refusé" / "Échec") instead of an internal explanation.
        if (finalStatus == DeliveryStatus.FAILED) {
            boolean anyRefused = delivery.getOrder() != null && delivery.getOrder().getItems() != null
                    && delivery.getOrder().getItems().stream()
                        .anyMatch(it -> it != null && "REFUSED".equalsIgnoreCase(it.getOutcome()));
            FailureCode code = anyRefused ? FailureCode.REFUSED : FailureCode.OTHER;
            // Cross-class now that the failure paths live in their own service. fail() is
            // @Transactional with the default REQUIRED propagation, so it joins this method's
            // transaction — exactly what the former self-invocation did by bypassing the proxy.
            return incidentService.fail(deliveryId, driverId, null, code, null, principal);
        }

        boolean treatedAsPartial = finalStatus == DeliveryStatus.PARTIALLY_DELIVERED;
        delivery.setStatus(finalStatus);
        delivery.setCompletedAt(LocalDateTime.now());
        delivery = deliveryRepo.save(delivery);

        // ADR-033 — A reverse-pickup leg reuses the original order, so it must NOT push a forward stock
        // sync to Odoo on completion (the return's stock is reversed later via stock.return.picking on
        // RESTOCKED). Instead, completing it at the depot advances its RMA to RECEIVED.
        if (isReturnPickup) {
            if (finalStatus == DeliveryStatus.DELIVERED && delivery.getRmaId() != null) {
                rmaService.onReturnCollected(delivery.getRmaId());
            }
        } else {
            // P1: Transactional Outbox. enqueueErpStockSync atomically marks the order PENDING_SYNC and
            // enqueues the event (B5), so we no longer reset the status by hand. `treatedAsPartial` is the
            // SERVER-derived verdict (C3), not the raw mobile flag, so Odoo gets a full-delivery sync
            // whenever every line was in fact delivered.
            outboxProcessor.enqueueErpStockSync(deliveryId, treatedAsPartial,
                    treatedAsPartial ? normalizedPartialItems : null);
        }

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";

        auditLogService.logAction(principal, "DRIVER_COMPLETE", "DELIVERY", delivery.getId().toString(),
            Map.of("driver", driverName, "client", clientName, "status", finalStatus.name(),
                   "action", treatedAsPartial ? "DELIVERY_PARTIALLY_DELIVERED" : "DELIVERY_COMPLETED"));

        String eventKey = treatedAsPartial ? "DELIVERY_PARTIALLY_DELIVERED" : "DELIVERY_COMPLETED";
        transitions.appendHistory(delivery, finalStatus, driverId.toString(), Role.DRIVER, eventKey, Map.of("driverId", driverId.toString()));
        routeExecutionService.syncStopFromDelivery(delivery.getId(), finalStatus, delivery.getCompletedAt(), eventKey);
        // Recompute the terminal SLA verdict NOW the stop's completedAt is stamped — otherwise the
        // earlier (in-transit) state stays, and since terminal statuses aren't reconciled it would
        // be stuck reporting on-time even for a late delivery.
        slaStateService.refresh(delivery);

        eventPublisher.publishDeliveryCompleted(delivery.getOrder(), delivery, driverId);

        Map<String, Object> statPayload = new HashMap<>();
        statPayload.put("driverId", driverId.toString());
        statPayload.put("stat", "delivered");
        outboxProcessor.enqueue("INCREMENT_DRIVER_STAT", statPayload);

        return mapper.toDriverDeliveryResponse(delivery);
    }

    private List<com.asm.delivery.dto.request.PartialDeliveryItem> normalizePartialItems(
            Order order,
            List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems
    ) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return partialItems;
        }

        Map<String, OrderItem> bySku    = new HashMap<>();
        Map<String, OrderItem> byItemId = new HashMap<>();
        Map<String, OrderItem> byName   = new HashMap<>();
        order.getItems().forEach(item -> {
            if (item == null) {
                return;
            }
            if (item.getSku() != null && !item.getSku().isBlank()) {
                bySku.put(item.getSku().trim(), item);
            }
            if (item.getId() != null && !item.getId().isBlank()) {
                byItemId.put(item.getId().trim(), item);
            }
            if (item.getName() != null && !item.getName().isBlank()) {
                byName.put(item.getName().trim(), item);
            }
        });

        List<com.asm.delivery.dto.request.PartialDeliveryItem> normalized = new java.util.ArrayList<>();
        for (com.asm.delivery.dto.request.PartialDeliveryItem input : partialItems) {
            if (input == null) {
                continue;
            }
            String raw = input.referenceKey();
            if (raw == null || raw.isBlank()) {
                continue;
            }

            OrderItem matched = bySku.get(raw);
            if (matched == null) matched = byItemId.get(raw);
            if (matched == null) matched = byName.get(raw);

            String resolvedSku = (matched != null && matched.getSku() != null && !matched.getSku().isBlank())
                    ? matched.getSku().trim()
                    : (matched != null && matched.getName() != null ? matched.getName().trim() : raw);
            int orderedQty = (matched != null && matched.getQuantity() != null) ? Math.max(matched.getQuantity(), 0) : 0;
            // WMS mode: collapse the per-unit segment breakdown into the single denormalized fields
            // (quantityDone = Σ DELIVERED, dominant outcome/reason) that the ERP payload + admin read.
            if (input.hasSegments()) collapseSegments(input, orderedQty);

            // C2 — Clamp to [0, orderedQty] so the items forwarded to Odoo can never carry an
            // over-delivery. The same clamp is applied again in applyPartialQuantities for persistence.
            int rawDone = Math.max(input.getQuantityDone() != null ? input.getQuantityDone() : 0, 0);
            if (orderedQty <= 0) orderedQty = rawDone;
            int qtyDone = Math.min(rawDone, orderedQty);

            com.asm.delivery.dto.request.PartialDeliveryItem normalizedItem =
                    new com.asm.delivery.dto.request.PartialDeliveryItem(resolvedSku, qtyDone);
            // Preserve per-item outcome, reason, comment and the per-unit segment breakdown.
            normalizedItem.setOutcome(input.effectiveOutcome());
            normalizedItem.setReason(input.getReason());
            if (input.getReason() != null) {
                normalizedItem.setReasonLabel(input.getReasonLabel() != null && !input.getReasonLabel().isBlank()
                        ? input.getReasonLabel()
                        : failureReasonService.findLabel(input.getReason()).orElse(null));
            }
            normalizedItem.setComment(input.getComment());
            normalizedItem.setSegments(input.getSegments());
            if (matched != null && matched.getName() != null) {
                normalizedItem.setName(matched.getName());
            }
            normalized.add(normalizedItem);
        }

        return normalized;
    }

    /**
     * Collapse a per-unit segment breakdown into the single denormalized fields the ERP + admin read:
     * quantityDone = Σ DELIVERED, and outcome/reason/label/comment from the largest non-delivered
     * ("dominant") segment. Snapshots each segment's reasonLabel. Mutates {@code in} in place; no-op
     * when the item carries no segments (legacy single-field path stays as-is).
     */
    private void collapseSegments(com.asm.delivery.dto.request.PartialDeliveryItem in, int orderedQty) {
        int delivered = 0;
        com.asm.delivery.entity.ItemSegment dominant = null;
        for (com.asm.delivery.entity.ItemSegment seg : in.getSegments()) {
            if (seg == null) continue;
            int q = seg.getQuantity() != null ? Math.max(seg.getQuantity(), 0) : 0;
            String disp = seg.getDisposition() != null ? seg.getDisposition().trim().toUpperCase() : "DELIVERED";
            seg.setDisposition(disp);
            if ("DELIVERED".equals(disp)) { delivered += q; continue; }
            if (seg.getReasonCode() != null && (seg.getReasonLabel() == null || seg.getReasonLabel().isBlank())) {
                seg.setReasonLabel(failureReasonService.findLabel(seg.getReasonCode()).orElse(null));
            }
            int domQ = (dominant != null && dominant.getQuantity() != null) ? dominant.getQuantity() : 0;
            if (dominant == null || q > domQ) dominant = seg;
        }
        in.setQuantityDone(orderedQty > 0 ? Math.min(delivered, orderedQty) : delivered);
        if (dominant == null) {
            in.setOutcome("DELIVERED");
            in.setReason(null);
            in.setReasonLabel(null);
        } else {
            in.setOutcome(dominant.getDisposition());
            in.setReason(dominant.getReasonCode());
            in.setReasonLabel(dominant.getReasonLabel());
            if (dominant.getComment() != null && !dominant.getComment().isBlank()) in.setComment(dominant.getComment());
        }
    }

    private void applyPartialQuantities(Order order, List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems) {
        if (order.getItems() == null || order.getItems().isEmpty()) return;

        // Build lookup maps keyed by SKU: quantity done, outcome, reason, comment, and segment breakdown
        Map<String, Integer> doneBySku     = new HashMap<>();
        Map<String, String>  outcomeBySku  = new HashMap<>();
        Map<String, String>  reasonBySku   = new HashMap<>();
        Map<String, String>  commentBySku  = new HashMap<>();
        Map<String, java.util.List<com.asm.delivery.entity.ItemSegment>> segmentsBySku = new HashMap<>();

        partialItems.forEach(item -> {
            String ref = item != null ? item.referenceKey() : null;
            if (ref == null || ref.isBlank()) return;
            doneBySku.put(ref, Math.max(item.getQuantityDone() != null ? item.getQuantityDone() : 0, 0));
            outcomeBySku.put(ref, item.effectiveOutcome());
            if (item.getReason() != null && !item.getReason().isBlank()) {
                reasonBySku.put(ref, item.getReason().toUpperCase());
            }
            if (item.getComment() != null && !item.getComment().isBlank()) {
                commentBySku.put(ref, item.getComment().trim());
            }
            if (item.hasSegments()) segmentsBySku.put(ref, item.getSegments());
        });

        order.getItems().forEach(item -> {
            if (item == null) return;
            // Prefer SKU as lookup key; fall back to name for items without a SKU (e.g. Odoo service lines)
            String key = (item.getSku() != null && !item.getSku().isBlank())
                    ? item.getSku().trim()
                    : (item.getName() != null ? item.getName().trim() : null);
            if (key == null) return;
            Integer done = doneBySku.get(key);
            if (done != null) {
                int planned = item.getQuantity() != null ? item.getQuantity() : 0;
                item.setQuantityDone(Math.min(done, Math.max(planned, 0)));
                item.setOutcome(outcomeBySku.get(key));
                String reasonCode = reasonBySku.get(key);
                item.setReason(reasonCode);
                // Snapshot the human label from the catalog (stable for history); null for non-catalog codes.
                item.setReasonLabel(reasonCode != null ? failureReasonService.findLabel(reasonCode).orElse(null) : null);
                item.setComment(commentBySku.get(key));
                item.setSegments(segmentsBySku.get(key)); // per-unit breakdown (null when the driver sent none)
            } else {
                // Item not mentioned by driver → infer as REFUSED with qty 0
                item.setQuantityDone(0);
                item.setOutcome("REFUSED");
                item.setSegments(null);
            }
        });
    }

    /**
     * C3 — Derives the real terminal status from the order line quantities the driver keyed in,
     * independently of the mobile {@code isPartial} flag (which can be wrong).
     * <ul>
     *   <li>no line delivered (every {@code quantityDone == 0}) → {@link DeliveryStatus#FAILED}</li>
     *   <li>every line delivered in full ({@code quantityDone == quantity}) → {@link DeliveryStatus#DELIVERED}</li>
     *   <li>anything in between → {@link DeliveryStatus#PARTIALLY_DELIVERED}</li>
     * </ul>
     * An order with no line items can't be reasoned about per-line, so we trust the partial intent
     * and return {@code PARTIALLY_DELIVERED}.
     */
    private DeliveryStatus deriveStatusFromQuantities(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return DeliveryStatus.PARTIALLY_DELIVERED;
        }
        int totalPlanned = 0;
        int totalDone = 0;
        boolean everyLineComplete = true;
        for (OrderItem item : order.getItems()) {
            if (item == null) continue;
            int planned = item.getQuantity() != null ? Math.max(item.getQuantity(), 0) : 0;
            int done = item.getQuantityDone() != null ? Math.max(item.getQuantityDone(), 0) : 0;
            totalPlanned += planned;
            totalDone += done;
            if (done < planned) everyLineComplete = false;
        }
        if (totalDone == 0) return DeliveryStatus.FAILED;
        if (everyLineComplete && totalDone >= totalPlanned) return DeliveryStatus.DELIVERED;
        return DeliveryStatus.PARTIALLY_DELIVERED;
    }

    // ── Submit Proof of Delivery (POD) ────────────────────────────────────────

    /**
     * Submits the proof of delivery.
     *
     * <p><b>Not transactional, by design.</b> The photos are uploaded to object storage <em>before</em>
     * the database transaction opens, and a failed upload aborts the submit.
     *
     * <p>They used to be uploaded after commit, where a failure could only be logged: the POD row,
     * the terminal delivery status and the {@code ERP_SYNC_POD} event were all committed anyway, so
     * the database recorded a photo key for an object that had never been written, the ERP received
     * a POD referencing bytes nobody could fetch, and nothing ever retried. A storage outage silently
     * destroyed the proof of delivery — the one artefact a delivery exists to produce.
     *
     * <p>Uploading first inverts the failure mode into the safe one: the driver gets an error and
     * retries with the photos still on the device, and nothing downstream ever references an object
     * that is not there. The keys are deterministic ({@code pod/{deliveryId}/…}), so a retry
     * overwrites rather than accumulates, and an upload orphaned by a later rollback is simply
     * reclaimed by the next attempt.
     */
    public DriverDeliveryResponse submitPod(UUID deliveryId, UUID driverId, ProofOfDeliveryRequest req, UserPrincipal principal) {
        final String blBase64 = req.getBonLivraisonPhotoBase64();
        final String pkgBase64 = req.getPackagePhotoBase64();
        // ADR-033 — the bon de livraison is optional for a return collection (no delivery note exists).
        final boolean hasBl = blBase64 != null && !blBase64.isBlank();

        String deliveryFolder = "pod/" + deliveryId;
        String bonLivraisonPhotoPath = deliveryFolder + "/bon-livraison.png";
        String packagePhotoPath = deliveryFolder + "/package.png";

        if (hasBl) uploadPodPhoto(blBase64, bonLivraisonPhotoPath, deliveryId, "bon-livraison");
        uploadPodPhoto(pkgBase64, packagePhotoPath, deliveryId, "package");

        // TransactionTemplate, not @Transactional: this method is now the entry point and calls the
        // persistence step through `this`, which never engages the proxy (self-invocation) — the work
        // below would otherwise run in separate implicit transactions.
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> persistAndCompletePod(
                        deliveryId, driverId, req.getComment(), req.getLat(), req.getLng(),
                        req.getRecipientName(), req.isPartial(), req.getItemsDone(), hasBl, principal,
                        bonLivraisonPhotoPath, packagePhotoPath, req.getCash()));
    }

    /** Stores one POD photo, turning a storage failure into a retryable 503 for the driver. */
    private void uploadPodPhoto(String base64, String path, UUID deliveryId, String label) {
        try {
            minioStorageService.uploadBase64(base64, path);
        } catch (Exception e) {
            log.error("POD_UPLOAD_FAILED deliveryId={} photo={} path={} reason={}",
                    deliveryId, label, path, e.getMessage());
            throw AppException.serviceUnavailable("POD_UPLOAD_FAILED",
                    "Impossible d'enregistrer les photos de livraison. Réessayez.");
        }
    }

    /** Shared POD persistence + completion + ERP sync. The photos named by the two paths are already
     *  in object storage by the time this runs — see {@link #submitPod}. */
    private DriverDeliveryResponse persistAndCompletePod(
            UUID deliveryId, UUID driverId, String comment, BigDecimal lat, BigDecimal lng,
            String recipientName,
            boolean partial, List<com.asm.delivery.dto.request.PartialDeliveryItem> itemsDone,
            boolean hasBonLivraison,
            UserPrincipal principal, String bonLivraisonPhotoPath, String packagePhotoPath,
            com.asm.delivery.dto.request.ProofOfDeliveryRequest.CashCollectionEntry cash) {
        Delivery delivery = transitions.loadAndAuthorize(deliveryId, driverId);

        // Idempotency FIRST: if a POD already exists, this submit is a duplicate/replay (double-tap,
        // client retry after a slow response, or offline-queue replay) of one that already completed.
        // Return the current state as success — the first submit already advanced the status past
        // IN_TRANSIT/PICKED_UP, so running the status guard before this would wrongly 400 a delivery
        // that is in fact DELIVERED/PARTIALLY_DELIVERED.
        if (podRepo.existsByDeliveryId(deliveryId)) {
            log.info("POD_DUPLICATE_SKIP deliveryId={} driverId={}", deliveryId, driverId);
            return mapper.toDriverDeliveryResponse(delivery);
        }

        if (delivery.getStatus() != DeliveryStatus.IN_TRANSIT
                && delivery.getStatus() != DeliveryStatus.PICKED_UP) {
            throw AppException.badRequest(
                    "INVALID_STATUS_FOR_POD",
                    "Cannot submit proof of delivery: current status is " + delivery.getStatus(),
                    Map.of("currentStatus", delivery.getStatus().name())
            );
        }

        // ADR-033 — a FORWARD delivery must carry its signed delivery-note (bon de livraison) photo; a
        // RETURN_PICKUP collection has none, only the collected-parcel photo.
        boolean isReturnPickup = delivery.getKind() == com.asm.delivery.entity.DeliveryKind.RETURN_PICKUP;
        if (!hasBonLivraison && !isReturnPickup) {
            throw AppException.badRequest("BON_LIVRAISON_REQUIRED",
                    "La photo du bon de livraison est obligatoire pour une livraison.");
        }

        // P0: Geofence Enforcement
        validateGeofence(delivery, lat, lng);

        // Persist the STORAGE KEY, not an absolute URL: a URL would freeze this server's address
        // into the row and break the photo the moment the host changes (new Wi-Fi/DHCP lease, VPS,
        // production domain). MediaUrlResolver turns the key into a caller-reachable URL at read time.
        String bonLivraisonPhotoUrl = hasBonLivraison ? minioStorageService.objectKeyFor(bonLivraisonPhotoPath) : null;
        String packagePhotoUrl = minioStorageService.objectKeyFor(packagePhotoPath);

        ProofOfDelivery pod = ProofOfDelivery.builder()
                .deliveryId(deliveryId)
                .bonLivraisonPhotoUrl(bonLivraisonPhotoUrl)
                .photoUrl(packagePhotoUrl)
                .signatureUrl(null)
                .comment(comment)
                .recipientName(recipientName)
                .lat(lat)
                .lng(lng)
                .collectedAt(LocalDateTime.now())
                .build();

        try {
            podRepo.save(pod);
        } catch (DataIntegrityViolationException ex) {
            log.warn("POD_DUPLICATE_RACE deliveryId={} driverId={} msg={}", deliveryId, driverId, ex.getMessage());
            Delivery latest = transitions.loadAndAuthorize(deliveryId, driverId);
            return mapper.toDriverDeliveryResponse(latest);
        }

        // C1 — Order matters in Odoo: the stock move (picking validation) must reach the ERP
        // BEFORE the proof of delivery, otherwise the POD attaches to a picking that is not yet
        // validated. The outbox processes events in insertion order, so we complete() first
        // (which enqueues ERP_SYNC_STOCK) and enqueue ERP_SYNC_POD only afterwards.
        DriverDeliveryResponse response = complete(deliveryId, driverId, partial, itemsDone, principal);

        // The money is recorded in the same transaction as the proof, because it changed hands in the
        // same moment. Never a gate: whatever is wrong or missing about the collection is written
        // down and surfaced at the depot, not used to refuse a delivery that physically happened.
        //
        // After complete(), not before: that call is what writes each line's delivered quantity, and
        // what the customer owes is computed from those. Run first, it read zeroes and expected
        // nothing of everyone.
        cashCollectionService.recordAtPod(
                deliveryRepo.findByIdWithOrder(deliveryId).map(Delivery::getOrder).orElse(delivery.getOrder()),
                deliveryId, driverId, cash);

        // C5 — The images are already in MinIO (uploaded before this transaction opened). The ERP
        // event carries their stable object keys, not the raw base64: the adapter fetches the bytes
        // and uploads them to Odoo. This keeps large binaries out of the outbox table and off the
        // RabbitMQ frames.
        Map<String, Object> podPayload = new HashMap<>();
        podPayload.put("deliveryId", deliveryId.toString());
        podPayload.put("deliveredAt", LocalDateTime.now().toString());
        if (recipientName != null && !recipientName.isBlank()) podPayload.put("recipientName", recipientName);
        if (comment != null) podPayload.put("comment", comment);
        if (lat != null) podPayload.put("lat", lat);
        if (lng != null) podPayload.put("lng", lng);
        if (bonLivraisonPhotoUrl != null) podPayload.put("bonLivraisonPhotoUrl", bonLivraisonPhotoUrl);
        if (packagePhotoUrl != null) podPayload.put("packagePhotoUrl", packagePhotoUrl);
        outboxProcessor.enqueue("ERP_SYNC_POD", podPayload);

        return response;
    }

    private void validateGeofence(Delivery delivery, BigDecimal driverLat, BigDecimal driverLng) {
        // GEOFENCE DISABLED FOR TESTING — re-enable before production
        // if (driverLat == null || driverLng == null) {
        //     throw AppException.badRequest("GPS_REQUIRED", "GPS coordinates are required to validate delivery.");
        // }

        // Order order = delivery.getOrder();
        // if (order == null || order.getDropoffLat() == null || order.getDropoffLng() == null) {
        //     log.warn("GEOFENCE_SKIP: Missing dropoff coordinates for delivery {}", delivery.getId());
        //     return;
        // }

        // double distance = calculateDistance(
        //     driverLat.doubleValue(), driverLng.doubleValue(),
        //     order.getDropoffLat().doubleValue(), order.getDropoffLng().doubleValue()
        // );

        // double maxRadius = 4000.0;

        // if (distance > maxRadius) {
        //     log.warn("GEOFENCE_REJECT deliveryId={} driverId={} distance={}m", delivery.getId(), delivery.getDriverId(), (int)distance);
        //     throw AppException.badRequest(
        //         "OUT_OF_GEOFENCE",
        //         String.format("Geofence violation: you are too far from the dropoff point (%d meters).", (int)distance),
        //         Map.of("distance", (int)distance)
        //     );
        // }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Radius of the earth in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c * 1000; // convert to meters
    }

    // ── Location update ───────────────────────────────────────────────────────

    @Transactional
    public void updateLocation(UUID driverId, BigDecimal lat, BigDecimal lng) {
        List<Delivery> active = deliveryRepo.findActiveForDriver(driverId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            Delivery delivery = active.get(0);
            trackingRepo.save(Tracking.builder()
                    .deliveryId(delivery.getId())
                    .lat(lat)
                    .lng(lng)
                    .build());
            // Push real-time location to admin dashboard and public tracking page
            eventPublisher.publishDriverLocation(driverId, lat, lng);
            eventPublisher.publishPublicDriverLocation(delivery.getId(), lat, lng);
        }
        // Best-effort, high-frequency: publish to the broker (fire-and-forget) instead of a
        // synchronous HTTP PUT, so DeliveryService is not coupled to DriverService uptime.
        driverCommandPublisher.publishLocationUpdate(driverId, lat, lng);
    }

    // ── Workflow service integration ──────────────────────────────────────────

    @Transactional
    public void resetToWaiting(UUID deliveryId) {
        deliveryRepo.findByIdWithOrder(deliveryId).ifPresent(delivery -> {
            if (delivery.getStatus() == DeliveryStatus.SCHEDULED && delivery.getDriverId() != null) {
                delivery.setStatus(DeliveryStatus.UNSCHEDULED);
                delivery.setDriverId(null);
                delivery.setAssignedAt(null);
                delivery.setWaitingSlaMinutes(null);
                delivery.setAssignSlaMinutes(null);
                delivery.setPickupSlaMinutes(null);
                deliveryRepo.save(delivery);
                transitions.appendHistory(delivery, DeliveryStatus.UNSCHEDULED, "SYSTEM", Role.SYSTEM, "DELIVERY_TIMEOUT_RESET", Map.of());
            }
        });
    }

    @Transactional
    public void forceCancel(UUID deliveryId, String reason) {
        deliveryRepo.findByIdWithOrder(deliveryId).ifPresent(delivery -> {
            if (delivery.getDriverId() != null) {
            }
            delivery.setStatus(DeliveryStatus.CANCELLED);
            delivery.setCancelledAt(LocalDateTime.now());
            delivery.setCancelledBy(Role.SYSTEM);
            delivery.setCancelReason(reason);
            deliveryRepo.save(delivery);
            transitions.appendHistory(delivery, DeliveryStatus.CANCELLED, "SYSTEM", Role.SYSTEM, "DELIVERY_CANCELLED_BY_SYSTEM", Map.of("reason", reason != null ? reason : ""));
            eventPublisher.publishDeliveryCancelled(delivery.getOrder(), delivery, null);
        });
    }

    @Transactional
    public void forceFail(UUID deliveryId, String reason) {
        deliveryRepo.findByIdWithOrder(deliveryId).ifPresent(delivery -> {
            if (delivery.getDriverId() != null) {
            }
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setFailedAt(LocalDateTime.now());
            delivery.setFailReason(reason);
            deliveryRepo.save(delivery);
            transitions.appendHistory(delivery, DeliveryStatus.FAILED, "SYSTEM", Role.SYSTEM, "DELIVERY_FAILED_BY_SYSTEM", Map.of("reason", reason != null ? reason : ""));
            routeExecutionService.syncStopFromDelivery(delivery.getId(), DeliveryStatus.FAILED, delivery.getFailedAt(), reason);
            eventPublisher.publishDeliveryFailed(delivery.getOrder(), delivery, reason);
        });
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Map<String, String> deliveryPayload(Delivery delivery, String status) {
        Order order = delivery.getOrder();
        String clientName = (order != null && order.getClientName() != null) ? order.getClientName() : "";
        String orderId = "";
        if (order != null) {
            if (order.getErpOrderId() != null) orderId = order.getErpOrderId();
            else if (order.getErpExternalRef() != null) orderId = order.getErpExternalRef();
            else if (order.getId() != null) orderId = order.getId().toString().substring(0, 8).toUpperCase();
        }
        Map<String, String> map = new HashMap<>();
        map.put("deliveryId", delivery.getId().toString());
        map.put("status", status);
        map.put("clientName", clientName);
        map.put("orderId", orderId);
        return map;
    }

}
