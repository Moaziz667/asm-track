package com.asm.delivery.service;

import com.asm.delivery.dto.request.ProofOfDeliveryRequest;
import com.asm.delivery.dto.request.IncidentReportRequest;
import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.dto.response.HandoffTokenResponse;
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

    private final DeliveryRepository              deliveryRepo;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final TrackingRepository              trackingRepo;
    private final DeliveryReportRepository        reportRepo;
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
    private final ProcessedRequestRepository      idempotencyRepo;
    private final ObjectMapper                    objectMapper;
    private final HandoffService                  handoffService;
    private final com.asm.delivery.repository.HandoffRepository handoffRepository;
    private final FailureReasonService            failureReasonService;

    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
    );

    private static final List<RouteStatus> ACTIVE_ROUTE_STATUSES = List.of(
            RouteStatus.VALIDATED,
            RouteStatus.IN_PROGRESS
    );

    // ── Get available deliveries ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DriverDeliveryResponse> getAvailable(UUID driverId) {
        return deliveryRepo.findAllWaitingWithOrder(DeliveryStatus.UNSCHEDULED).stream()
                .map(this::toDriverDeliveryResponse)
                .toList();
    }

    // ── Get active delivery for driver ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DriverDeliveryResponse> getActive(UUID driverId) {
        List<DriverDeliveryResponse> active = deliveryRepo.findActiveForDriver(driverId, ACTIVE_STATUSES).stream()
                .map(this::toDriverDeliveryResponse)
                .collect(Collectors.toList());

        // Also include deliveries where this driver is the handoff SENDER (package still physically with them)
        List<UUID> activeIds = active.stream()
                .map(r -> r.getDeliveryId())
                .collect(Collectors.toList());
        routeStopRepository.findPendingHandoffsByFromDriverWithRoute(driverId).stream()
                .map(stop -> deliveryRepo.findByIdWithOrder(stop.getDeliveryId()).orElse(null))
                .filter(d -> d != null && !activeIds.contains(d.getId()))
                .map(this::toDriverDeliveryResponse)
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

        return toDriverDeliveryResponse(delivery);
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

        appendHistory(delivery, DeliveryStatus.SCHEDULED, driverId.toString(), Role.DRIVER, "DELIVERY_SCHEDULED_BY_DRIVER", Map.of("driverId", driverId.toString()));
        eventPublisher.publishDeliveryScheduled(delivery.getOrder(), delivery, driverId);

        return toDriverDeliveryResponse(delivery);
    }
    @Transactional
    public DriverDeliveryResponse pickup(UUID deliveryId, UUID driverId, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        assertStatus(delivery, DeliveryStatus.SCHEDULED, "pickup");

        delivery.setStatus(DeliveryStatus.PICKED_UP);
        delivery.setPickedUpAt(LocalDateTime.now());
        delivery.setAssignSlaMinutes(delayCalculationService.calculateAssignSlaMinutes(delivery));
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_PICKUP", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "action", "Ramassage du colis"));
        appendHistory(delivery, DeliveryStatus.PICKED_UP, driverId.toString(), Role.DRIVER, "DELIVERY_PICKED_UP", Map.of("driverId", driverId.toString()));
        eventPublisher.publishDeliveryPickedUp(delivery.getOrder(), delivery);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Transit ───────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse transit(UUID deliveryId, UUID driverId, BigDecimal lat, BigDecimal lng, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        assertStatus(delivery, DeliveryStatus.PICKED_UP, "start transit");

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
        appendHistory(delivery, DeliveryStatus.IN_TRANSIT, driverId.toString(), Role.DRIVER, "DELIVERY_TRANSIT_STARTED", Map.of("driverId", driverId.toString()));
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

        return toDriverDeliveryResponse(delivery);
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse complete(UUID deliveryId, UUID driverId, UserPrincipal principal) {
        return complete(deliveryId, driverId, false, null, principal);
    }

    @Transactional
    public DriverDeliveryResponse complete(UUID deliveryId, UUID driverId, boolean isPartial, List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        if (delivery.getStatus() != DeliveryStatus.IN_TRANSIT
                && delivery.getStatus() != DeliveryStatus.PICKED_UP) {
            throw AppException.badRequest("Cannot complete from status " + delivery.getStatus());
        }

        List<com.asm.delivery.dto.request.PartialDeliveryItem> normalizedPartialItems = partialItems;
        if (isPartial && delivery.getOrder() != null && partialItems != null && !partialItems.isEmpty()) {
            normalizedPartialItems = normalizePartialItems(delivery.getOrder(), partialItems);
        }

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
            }
        }

        DeliveryStatus finalStatus = isPartial ? DeliveryStatus.PARTIALLY_DELIVERED : DeliveryStatus.DELIVERED;
        delivery.setStatus(finalStatus);
        delivery.setCompletedAt(LocalDateTime.now());
        // Mark the order as pending sync BEFORE saving and enqueueing.
        // The Order default is "SYNCED", so without this the OutboxProcessor
        // sees SYNCED and silently skips the event without ever calling Odoo.
        if (delivery.getOrder() != null) {
            delivery.getOrder().setOdooSyncStatus("PENDING_SYNC");
        }
        delivery = deliveryRepo.save(delivery);

        // P1: Transactional Outbox Pattern
        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("deliveryId", deliveryId.toString());
        outboxPayload.put("isPartial", isPartial);
        if (isPartial && normalizedPartialItems != null) {
            outboxPayload.put("partialItems", normalizedPartialItems);
        }
        outboxProcessor.enqueue("ERP_SYNC_STOCK", outboxPayload);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        
        auditLogService.logAction(principal, "DRIVER_COMPLETE", "DELIVERY", delivery.getId().toString(),
            Map.of("driver", driverName, "client", clientName, "status", finalStatus.name(),
                   "action", isPartial ? "DELIVERY_PARTIALLY_DELIVERED" : "DELIVERY_COMPLETED"));

        String eventKey = isPartial ? "DELIVERY_PARTIALLY_DELIVERED" : "DELIVERY_COMPLETED";
        appendHistory(delivery, finalStatus, driverId.toString(), Role.DRIVER, eventKey, Map.of("driverId", driverId.toString()));
        routeExecutionService.syncStopFromDelivery(delivery.getId(), finalStatus, delivery.getCompletedAt(), eventKey);
        
        eventPublisher.publishDeliveryCompleted(delivery.getOrder(), delivery, driverId);

        Map<String, Object> statPayload = new HashMap<>();
        statPayload.put("driverId", driverId.toString());
        statPayload.put("stat", "delivered");
        outboxProcessor.enqueue("INCREMENT_DRIVER_STAT", statPayload);

        return toDriverDeliveryResponse(delivery);
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
            int qtyDone = Math.max(input.getQuantityDone() != null ? input.getQuantityDone() : 0, 0);

            com.asm.delivery.dto.request.PartialDeliveryItem normalizedItem =
                    new com.asm.delivery.dto.request.PartialDeliveryItem(resolvedSku, qtyDone);
            // Preserve per-item outcome, reason, and comment supplied by the driver app
            normalizedItem.setOutcome(input.effectiveOutcome());
            normalizedItem.setReason(input.getReason());
            normalizedItem.setComment(input.getComment());
            // Set item display name from matched OrderItem for readable Odoo notes
            if (matched != null && matched.getName() != null) {
                normalizedItem.setName(matched.getName());
            }
            normalized.add(normalizedItem);
        }

        return normalized;
    }

    private void applyPartialQuantities(Order order, List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems) {
        if (order.getItems() == null || order.getItems().isEmpty()) return;

        // Build lookup maps keyed by SKU: quantity done, outcome, reason, and comment
        Map<String, Integer> doneBySku     = new HashMap<>();
        Map<String, String>  outcomeBySku  = new HashMap<>();
        Map<String, String>  reasonBySku   = new HashMap<>();
        Map<String, String>  commentBySku  = new HashMap<>();

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
                item.setReason(reasonBySku.get(key));
                item.setComment(commentBySku.get(key));
            } else {
                // Item not mentioned by driver → infer as REFUSED with qty 0
                item.setQuantityDone(0);
                item.setOutcome("REFUSED");
            }
        });
    }

    // ── Submit Proof of Delivery (POD) ────────────────────────────────────────


    @Transactional
    public DriverDeliveryResponse submitPod(UUID deliveryId, UUID driverId, ProofOfDeliveryRequest req, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        if (delivery.getStatus() != DeliveryStatus.IN_TRANSIT
                && delivery.getStatus() != DeliveryStatus.PICKED_UP) {
            throw AppException.badRequest(
                    "INVALID_STATUS_FOR_POD",
                    "Cannot submit proof of delivery: current status is " + delivery.getStatus(),
                    Map.of("currentStatus", delivery.getStatus().name())
            );
        }

        // P0: Geofence Enforcement
        validateGeofence(delivery, req.getLat(), req.getLng());

        if (podRepo.existsByDeliveryId(deliveryId)) {
            log.info("POD_DUPLICATE_SKIP deliveryId={} driverId={}", deliveryId, driverId);
            return toDriverDeliveryResponse(delivery);
        }

        // P2: Deterministic Object Storage
        String deliveryFolder = "pod/" + deliveryId;
        String bonLivraisonPhotoPath = deliveryFolder + "/bon-livraison.png";
        String packagePhotoPath = deliveryFolder + "/package.png";

        String bonLivraisonPhotoUrl = minioStorageService.getPublicUrl(bonLivraisonPhotoPath);
        String packagePhotoUrl = minioStorageService.getPublicUrl(packagePhotoPath);

        ProofOfDelivery pod = ProofOfDelivery.builder()
                .deliveryId(deliveryId)
                .bonLivraisonPhotoUrl(bonLivraisonPhotoUrl)
                .photoUrl(packagePhotoUrl)
                .signatureUrl(null)
                .comment(req.getComment())
                .lat(req.getLat())
                .lng(req.getLng())
                .collectedAt(LocalDateTime.now())
                .build();

        try {
            podRepo.save(pod);
        } catch (DataIntegrityViolationException ex) {
            log.warn("POD_DUPLICATE_RACE deliveryId={} driverId={} msg={}", deliveryId, driverId, ex.getMessage());
            Delivery latest = loadAndAuthorize(deliveryId, driverId);
            return toDriverDeliveryResponse(latest);
        }

        // Defer upload to MinIO until the database transaction successfully commits
        String blBase64 = req.getBonLivraisonPhotoBase64();
        String pkgBase64 = req.getPackagePhotoBase64();
        runAfterCommit(() -> {
            try {
                minioStorageService.uploadBase64(blBase64, bonLivraisonPhotoPath);
            } catch (Exception e) {
                log.error("Deferred post-commit upload failed for bon-livraison photo of delivery {}: {}", deliveryId, e.getMessage());
            }
            try {
                minioStorageService.uploadBase64(pkgBase64, packagePhotoPath);
            } catch (Exception e) {
                log.error("Deferred post-commit upload failed for package photo of delivery {}: {}", deliveryId, e.getMessage());
            }
        });

        // Push the proof of delivery to the ERP (signature/photos + metadata) via the outbox.
        Map<String, Object> podPayload = new HashMap<>();
        podPayload.put("deliveryId", deliveryId.toString());
        podPayload.put("deliveredAt", LocalDateTime.now().toString());
        if (req.getComment() != null) podPayload.put("comment", req.getComment());
        if (req.getLat() != null) podPayload.put("lat", req.getLat());
        if (req.getLng() != null) podPayload.put("lng", req.getLng());
        if (blBase64 != null) podPayload.put("blPhotoBase64", blBase64);
        if (pkgBase64 != null) podPayload.put("packagePhotoBase64", pkgBase64);
        outboxProcessor.enqueue("ERP_SYNC_POD", podPayload);

        return complete(deliveryId, driverId, req.isPartial(), req.getItemsDone(), principal);
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

    // ── Fail ──────────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse fail(UUID deliveryId, UUID driverId, String failureReasonCode,
                                       FailureCode legacyCode, String failureComment, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        if (delivery.getStatus() != DeliveryStatus.PICKED_UP && delivery.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw AppException.badRequest("Can only fail delivery from PICKED_UP or IN_TRANSIT state");
        }

        // Resolve the configurable reason → analytics category + human label.
        final FailureCode failureCode;
        final String reasonLabel;
        if (failureReasonCode != null && !failureReasonCode.isBlank()) {
            FailureReasonService.Resolved resolved = failureReasonService.resolve(failureReasonCode);
            failureCode = resolved.category();
            reasonLabel = resolved.label();
        } else {
            failureCode = legacyCode != null ? legacyCode : FailureCode.OTHER;
            reasonLabel = failureCode.name();
        }
        // Persist a human-readable reason: label enriched with the free-text comment when present.
        String storedReason = (failureComment != null && !failureComment.isBlank())
                ? reasonLabel + " — " + failureComment.trim()
                : reasonLabel;

        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setFailReason(storedReason);
        delivery.setFailureCode(failureCode);
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_FAIL", "DELIVERY", delivery.getId().toString(),
            Map.of("driver", driverName, "client", clientName, "code", String.valueOf(failureCode),
                   "reason", failureComment != null ? failureComment : "", "action", "DELIVERY_FAILED"));

        // Release driver + increment stat (best-effort)
        Map<String, Object> failedPayload = new HashMap<>();
        failedPayload.put("driverId", driverId.toString());
        failedPayload.put("stat", "failed");
        outboxProcessor.enqueue("INCREMENT_DRIVER_STAT", failedPayload);

        appendHistory(delivery, DeliveryStatus.FAILED, driverId.toString(), Role.DRIVER, "DELIVERY_FAILED",
                Map.of("driverId", driverId.toString(), "reason", failureComment != null ? failureComment : "", "code", failureCode != null ? failureCode.name() : ""));
        routeExecutionService.syncStopFromDelivery(delivery.getId(), DeliveryStatus.FAILED, delivery.getFailedAt(), failureComment);
        eventPublisher.publishDeliveryFailed(delivery.getOrder(), delivery, failureComment);

        // P1: Outbox Sync for failures
        outboxProcessor.enqueue("ERP_SYNC_FAILURE", Map.of(
            "deliveryId", deliveryId.toString(),
            "failureCode", failureCode != null ? failureCode.name() : "GENERAL",
            "comment", failureComment != null ? failureComment : ""
        ));



        return toDriverDeliveryResponse(delivery);
    }

    // ── Cancel (driver cancels → back to UNSCHEDULED) ─────────────────────

    @Transactional
    public DriverDeliveryResponse cancelByDriver(UUID deliveryId, UUID driverId, String reason, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        if (delivery.getStatus() != DeliveryStatus.SCHEDULED) {
            throw AppException.badRequest("Driver can only cancel from SCHEDULED state");
        }

        delivery.setStatus(DeliveryStatus.UNSCHEDULED);
        delivery.setDriverId(null);
        delivery.setAssignedAt(null);
        delivery.setWaitingSlaMinutes(null);
        delivery.setAssignSlaMinutes(null);
        delivery.setPickupSlaMinutes(null);
        delivery = deliveryRepo.save(delivery);

        // Release driver + increment stat (best-effort)
        Map<String, Object> cancelPayload = new HashMap<>();
        cancelPayload.put("driverId", driverId.toString());
        cancelPayload.put("stat", "cancelled");
        outboxProcessor.enqueue("INCREMENT_DRIVER_STAT", cancelPayload);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_CANCEL", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "motif", StringUtils.hasText(reason) ? reason : "aucun",
                       "action", "Annulation par le chauffeur"));
        appendHistory(delivery, DeliveryStatus.UNSCHEDULED, driverId.toString(), Role.DRIVER,
                "DELIVERY_CANCELLED_BY_DRIVER",
                Map.of("driverId", driverId.toString(), "reason", StringUtils.hasText(reason) ? reason : ""));

        eventPublisher.publishDeliveryCancelled(delivery.getOrder(), delivery, driverId);



        return toDriverDeliveryResponse(delivery);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    @Transactional
    public void report(UUID deliveryId, UUID driverId, ReportType reportType, String description, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        reportRepo.save(DeliveryReport.builder()
                .deliveryId(delivery.getId())
                .driverId(driverId)
                .reportType(reportType)
                .description(description)
                .build());

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        auditLogService.logAction(principal, "DRIVER_REPORT", "DELIVERY", deliveryId.toString(),
                Map.of("chauffeur", driverName, "type", String.valueOf(reportType), "details", description != null ? description : "",
                       "action", "Signalement soumis"));
    }

    @Transactional
    public void reportIncident(UUID driverId, IncidentReportRequest req, UserPrincipal principal) {
        java.util.List<String> photoUrls = new java.util.ArrayList<>();
        
        if (req.getPhotosBase64() != null) {
            for (int i = 0; i < req.getPhotosBase64().size(); i++) {
                String objectPath = "reports/" + driverId + "/" + System.currentTimeMillis() + "-" + i + ".png";
                photoUrls.add(minioStorageService.uploadBase64(req.getPhotosBase64().get(i), objectPath));
            }
        }

        DeliveryReport report = DeliveryReport.builder()
                .deliveryId(req.getDeliveryId())
                .driverId(driverId)
                .reportType(req.getReportType())
                .description(req.getDescription())
                .lat(req.getLat())
                .lng(req.getLng())
                .photoUrls(photoUrls)
                .build();

        reportRepo.save(report);

        String targetId = req.getDeliveryId() != null ? req.getDeliveryId().toString() : "GENERAL";
        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        
        auditLogService.logAction(principal, "REPORT_INCIDENT", "INCIDENT", targetId,
                java.util.Map.of(
                    "chauffeur", driverName,
                    "type", req.getReportType().name(), 
                    "photos", photoUrls.size(), 
                    "action", "Signalement d'incident pro"
                ));
    }

    // ── Handoff confirmation (Driver B confirms physical receipt) ──────────────

    /**
     * Legacy delivery-id-keyed endpoints — thin adapters over {@link HandoffService},
     * which owns the lifecycle, hardened token, evidence and real-time events.
     */
    @Transactional
    public HandoffTokenResponse generateHandoffToken(UUID deliveryId, UUID driverId) {
        Handoff handoff = handoffRepository.findActiveByDeliveryId(deliveryId)
                .orElseThrow(() -> AppException.badRequest("This delivery is not awaiting a handoff"));
        Handoff updated = handoffService.generateToken(handoff.getId(), driverId);
        return HandoffTokenResponse.builder()
                .token(updated.getToken())
                .deliveryId(deliveryId.toString())
                .expiresAt(updated.getTokenExpiresAt())
                .build();
    }

    @Transactional
    public DriverDeliveryResponse confirmHandoff(UUID deliveryId, UUID driverId, String token, UserPrincipal principal) {
        return confirmHandoff(deliveryId, driverId, token, null, null, null, principal);
    }

    @Transactional
    public DriverDeliveryResponse confirmHandoff(UUID deliveryId, UUID driverId, String token,
            java.math.BigDecimal lat, java.math.BigDecimal lng, String notes, UserPrincipal principal) {
        Handoff handoff = handoffRepository.findActiveByDeliveryId(deliveryId).orElse(null);
        if (handoff == null) {
            // No open handoff — already confirmed or never required: return current state idempotently.
            Delivery current = deliveryRepo.findByIdWithOrder(deliveryId)
                    .orElseThrow(() -> AppException.notFound("Delivery not found"));
            return toDriverDeliveryResponse(current);
        }
        Delivery delivery = handoffService.confirm(handoff.getId(), driverId, token, lat, lng, null, notes);
        return toDriverDeliveryResponse(delivery);
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
                appendHistory(delivery, DeliveryStatus.UNSCHEDULED, "SYSTEM", Role.SYSTEM, "DELIVERY_TIMEOUT_RESET", Map.of());
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
            appendHistory(delivery, DeliveryStatus.CANCELLED, "SYSTEM", Role.SYSTEM, "DELIVERY_CANCELLED_BY_SYSTEM", Map.of("reason", reason != null ? reason : ""));
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
            appendHistory(delivery, DeliveryStatus.FAILED, "SYSTEM", Role.SYSTEM, "DELIVERY_FAILED_BY_SYSTEM", Map.of("reason", reason != null ? reason : ""));
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

    private Delivery loadAndAuthorize(UUID deliveryId, UUID driverId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (!driverId.equals(delivery.getDriverId())) {
            throw AppException.forbidden("Not your delivery");
        }
        return delivery;
    }

    private void assertStatus(Delivery delivery, DeliveryStatus expected, String action) {
        if (delivery.getStatus() != expected) {
            throw AppException.badRequest("Cannot " + action + " from status " + delivery.getStatus());
        }
    }

    private void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role, String eventKey, Map<String, Object> params) {
        String jsonParams = "{}";
        try {
            jsonParams = objectMapper.writeValueAsString(params != null ? params : Map.of());
        } catch (Exception ignored) {}
        
        historyRepo.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .eventKey(eventKey)
                .eventParams(jsonParams)
                .build());
    }

    public DriverDeliveryResponse toDriverDeliveryResponse(Delivery delivery) {
        // Look up handoff info from the active route stop
        RouteStop activeStop = routeStopRepository.findActiveByDeliveryId(delivery.getId()).orElse(null);
        boolean requiresHandoff = activeStop != null && Boolean.TRUE.equals(activeStop.getRequiresHandoff());

        Order order = delivery.getOrder();
        String orderRef = order != null ? order.resolveRef() : null;

        return DriverDeliveryResponse.builder()
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
                .orderRef(orderRef)
                .clientName(order != null ? order.getClientName() : null)
                .clientPhone(order != null ? order.getClientPhone() : null)
                .status(delivery.getStatus().name())
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .dropoffCity(order != null ? order.getDropoffCity() : null)
                .dropoffLat(order != null ? order.getDropoffLat() : null)
                .dropoffLng(order != null ? order.getDropoffLng() : null)
                .deliveryInstructions(order != null ? order.getDeliveryInstructions() : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .currency(order != null ? order.getCurrency() : null)
                .items(order != null ? order.getItems() : null)
                .totalQuantity(order != null ? order.getTotalQuantity() : null)
                .priority(order != null ? order.getPriority().name() : null)
                .scheduledAt(order != null ? order.getScheduledAt() : null)
                .assignedAt(delivery.getAssignedAt())
                .pickedUpAt(delivery.getPickedUpAt())
                .inTransitAt(delivery.getInTransitAt())
                .routeGeometry(delivery.getRouteGeometry())
                .routeDistanceKm(delivery.getRouteDistanceKm())
                .routeDurationMinutes(delivery.getRouteDurationMinutes())
                .transitSlaMinutesComputed(delivery.getTransitSlaMinutesComputed())
                .routeEtaAt(delivery.getRouteEtaAt())
                .routeProvider(delivery.getRouteProvider())
                .completedAt(delivery.getCompletedAt())
                .failedAt(delivery.getFailedAt())
                .cancelledAt(delivery.getCancelledAt())
                .failReason(delivery.getFailReason())
                .cancelReason(delivery.getCancelReason())
                .createdAt(delivery.getCreatedAt())
                .requiresHandoff(requiresHandoff)
                .handoffConfirmedAt(requiresHandoff && activeStop.getHandoffConfirmedAt() != null ? activeStop.getHandoffConfirmedAt() : null)
                .handoffToDriverId(requiresHandoff && activeStop.getHandoffToDriverId() != null ? activeStop.getHandoffToDriverId().toString() : null)
                .handoffFromDriverId(requiresHandoff && activeStop.getHandoffFromDriverId() != null ? activeStop.getHandoffFromDriverId().toString() : null)
                .build();
    }

    private void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }
            );
        } else {
            action.run();
        }
    }
}
