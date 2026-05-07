package com.asm.delivery.service;

import com.asm.delivery.dto.request.ProofOfDeliveryRequest;
import com.asm.delivery.dto.request.IncidentReportRequest;
import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.dto.response.HandoffTokenResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.erp.ErpSyncService;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.repository.*;
import com.asm.delivery.storage.MinioStorageService;
import java.time.LocalDate;
import com.asm.delivery.storage.StorageException;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.transport.DriverDTO;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final ErpSyncService                 ErpSyncService;
    private final ProofOfDeliveryRepository       podRepo;
    private final TransportPort                   transportPort;
    private final MinioStorageService             minioStorageService;
    private final com.asm.delivery.service.route.RouteExecutionService routeExecutionService;
    private final DelayCalculationService         delayCalculationService;
    private final AuditLogService                  auditLogService;
    private final RouteRepository                 routeRepository;
    private final RouteStopRepository             routeStopRepository;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
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
        return deliveryRepo.findActiveForDriver(driverId, ACTIVE_STATUSES).stream()
                .map(this::toDriverDeliveryResponse)
                .collect(Collectors.toList());
    }

    // ── Get a specific delivery (with driver authorization check) ─────────────

    @Transactional(readOnly = true)
    public DriverDeliveryResponse getDelivery(UUID deliveryId, UUID driverId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (delivery.getDriverId() != null && !delivery.getDriverId().equals(driverId)
                && delivery.getStatus() != DeliveryStatus.UNSCHEDULED) {
            throw AppException.forbidden("Not your delivery");
        }

        return toDriverDeliveryResponse(delivery);
    }

    // ── Accept delivery (atomic) ──────────────────────────────────────────────

    private static final List<RouteStatus> ACTIVE_ROUTE_STATUSES = List.of(
            RouteStatus.VALIDATED,
            RouteStatus.IN_PROGRESS
    );

    @Transactional
    public DriverDeliveryResponse accept(UUID deliveryId, UUID driverId, UserPrincipal principal) {
        // Driver cannot accept standalone deliveries while assigned to an active route today.
        // Route-level check is correct — a driver may have many deliveries within a single route.
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

        // Mark driver unavailable via Driver Service (best-effort)

        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_ACCEPT", "DELIVERY", deliveryId.toString(),
                Map.of("chauffeur", driverName, "client", clientName, "action", "Acceptation de livraison"));

        appendHistory(delivery, DeliveryStatus.SCHEDULED, driverId.toString(), Role.DRIVER, "Driver accepted delivery");
        eventPublisher.publishDeliveryScheduled(delivery.getOrder(), delivery, driverId);

        try { messagingTemplate.convertAndSend("/topic/admin/deliveries", deliveryPayload(delivery, "SCHEDULED")); } catch(Exception ignored){}
        
        return toDriverDeliveryResponse(delivery);
    }

    // ── Pickup ────────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse pickup(UUID deliveryId, UUID driverId, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        assertStatus(delivery, DeliveryStatus.SCHEDULED, "pickup");

        delivery.setStatus(DeliveryStatus.PICKED_UP);
        delivery.setPickedUpAt(LocalDateTime.now());
        delivery.setAssignSlaMinutes(delayCalculationService.calculateAssignSlaMinutes(delivery));
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_PICKUP", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "action", "Ramassage du colis"));
        appendHistory(delivery, DeliveryStatus.PICKED_UP, driverId.toString(), Role.DRIVER, "Package picked up");
        eventPublisher.publishDeliveryPickedUp(delivery.getOrder(), delivery);

        try { messagingTemplate.convertAndSend("/topic/admin/deliveries", deliveryPayload(delivery, "PICKED_UP")); } catch(Exception ignored){}

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
        if (originLat == null || originLng == null) {
            DriverDTO driver = transportPort.getDriver(driverId.toString());
            if (driver != null && driver.getCurrentLat() != null && driver.getCurrentLng() != null) {
                originLat = BigDecimal.valueOf(driver.getCurrentLat());
                originLng = BigDecimal.valueOf(driver.getCurrentLng());
            }
        }

        String transitNote = "Driver started transit";
        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_TRANSIT", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "action", "Debut du transit"));
        appendHistory(delivery, DeliveryStatus.IN_TRANSIT, driverId.toString(), Role.DRIVER, transitNote);
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

        try { messagingTemplate.convertAndSend("/topic/admin/deliveries", deliveryPayload(delivery, "IN_TRANSIT")); } catch(Exception ignored){}

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
        }

        DeliveryStatus finalStatus = isPartial ? DeliveryStatus.PARTIALLY_DELIVERED : DeliveryStatus.DELIVERED;
        delivery.setStatus(finalStatus);
        delivery.setCompletedAt(LocalDateTime.now());
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_COMPLETE", "DELIVERY", delivery.getId().toString(),
            Map.of("chauffeur", driverName, "client", clientName, "statut", finalStatus.name(),
                   "action", isPartial ? "Livraison partielle" : "Livraison completee"));

        String message = isPartial ? "Delivery partially completed" : "Delivery completed";
        appendHistory(delivery, finalStatus, driverId.toString(), Role.DRIVER, message);
        routeExecutionService.syncStopFromDelivery(delivery.getId(), finalStatus, delivery.getCompletedAt(), message);
        
        // TODO: eventPublisher.publishDeliveryPartiallyCompleted may be needed in the future
        // For now we can use the same event or add conditionally. 
        // We'll publish completed event. Or is there a specific logic in the subscriber?
        eventPublisher.publishDeliveryCompleted(delivery.getOrder(), delivery, driverId);

        // Release driver + increment stat (best-effort)
        String stat = isPartial ? "partial" : "delivered";
        transportPort.incrementStat(driverId.toString(), stat);

        // Sync Odoo
        if (delivery.getOrder() != null) {
            if (isPartial) {
                // If there's partial logic in Odoo sync, handle it here. Else sync normally.
                if (normalizedPartialItems != null) {
                    ErpSyncService.syncPartialStockUpdate(delivery.getOrder(), normalizedPartialItems);
                } else {
                    ErpSyncService.syncStockUpdate(delivery.getOrder());
                }
            } else {
                ErpSyncService.syncStockUpdate(delivery.getOrder());
            }
        }

        try { messagingTemplate.convertAndSend("/topic/admin/deliveries", deliveryPayload(delivery, finalStatus.name())); } catch(Exception ignored){}

        return toDriverDeliveryResponse(delivery);
    }

    private List<com.asm.delivery.dto.request.PartialDeliveryItem> normalizePartialItems(
            Order order,
            List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems
    ) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return partialItems;
        }

        Map<String, OrderItem> bySku = new HashMap<>();
        Map<String, OrderItem> byItemId = new HashMap<>();
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
            if (matched == null) {
                matched = byItemId.get(raw);
            }

            String resolvedSku = matched != null && matched.getSku() != null && !matched.getSku().isBlank()
                    ? matched.getSku().trim()
                    : raw;
            int qtyDone = Math.max(input.getQuantityDone() != null ? input.getQuantityDone() : 0, 0);
            normalized.add(new com.asm.delivery.dto.request.PartialDeliveryItem(resolvedSku, qtyDone));
        }

        return normalized;
    }

    private void applyPartialQuantities(Order order, List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return;
        }

        Map<String, Integer> doneBySku = new HashMap<>();
        partialItems.forEach(item -> {
            String ref = item != null ? item.referenceKey() : null;
            if (ref != null && !ref.isBlank()) {
                doneBySku.put(ref, Math.max(item.getQuantityDone() != null ? item.getQuantityDone() : 0, 0));
            }
        });

        order.getItems().forEach(item -> {
            if (item == null || item.getSku() == null) {
                return;
            }
            Integer done = doneBySku.get(item.getSku().trim());
            if (done != null) {
                int planned = item.getQuantity() != null ? item.getQuantity() : 0;
                item.setQuantityDone(Math.min(done, Math.max(planned, 0)));
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
                    "Cannot submit POD from status " + delivery.getStatus());
        }

        if (podRepo.existsByDeliveryId(deliveryId)) {
            log.info("POD_DUPLICATE_SKIP deliveryId={} driverId={}", deliveryId, driverId);
            return toDriverDeliveryResponse(delivery);
        }

        // Upload 2 mandatory photos to MinIO
        String deliveryFolder = "pod/" + deliveryId;
        long ts = System.currentTimeMillis();

        String bonLivraisonPhotoUrl;
        try {
            bonLivraisonPhotoUrl = minioStorageService.uploadBase64(
                    req.getBonLivraisonPhotoBase64(),
                    deliveryFolder + "/bon-livraison-" + ts + ".png");
        } catch (StorageException e) {
            log.error("Failed to upload bon-livraison photo for delivery {}: {}", deliveryId, e.getMessage());
            throw AppException.serviceUnavailable("Failed to store bon de livraison photo. Please retry.");
        }

        String packagePhotoUrl;
        try {
            packagePhotoUrl = minioStorageService.uploadBase64(
                    req.getPackagePhotoBase64(),
                    deliveryFolder + "/package-" + (ts + 1) + ".png");
        } catch (StorageException e) {
            log.error("Failed to upload package photo for delivery {}: {}", deliveryId, e.getMessage());
            throw AppException.serviceUnavailable("Failed to store package photo. Please retry.");
        }

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
            // Handles race condition where duplicate POD submissions arrive concurrently.
            log.warn("POD_DUPLICATE_RACE deliveryId={} driverId={} msg={}", deliveryId, driverId, ex.getMessage());
            Delivery latest = loadAndAuthorize(deliveryId, driverId);
            return toDriverDeliveryResponse(latest);
        }

        return complete(deliveryId, driverId, req.isPartial(), req.getItemsDone(), principal);
    }

    // ── Fail ──────────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse fail(UUID deliveryId, UUID driverId, FailureCode failureCode, String failureComment, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        if (delivery.getStatus() != DeliveryStatus.PICKED_UP && delivery.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw AppException.badRequest("Can only fail delivery from PICKED_UP or IN_TRANSIT state");
        }

        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setFailReason(failureComment);
        delivery.setFailureCode(failureCode);
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_FAIL", "DELIVERY", delivery.getId().toString(),
            Map.of("chauffeur", driverName, "client", clientName, "code", String.valueOf(failureCode),
                   "motif", failureComment != null ? failureComment : "", "action", "Livraison echouee"));

        // Release driver + increment stat (best-effort)
        transportPort.incrementStat(driverId.toString(), "failed");

        appendHistory(delivery, DeliveryStatus.FAILED, driverId.toString(), Role.DRIVER, failureComment);
        routeExecutionService.syncStopFromDelivery(delivery.getId(), DeliveryStatus.FAILED, delivery.getFailedAt(), failureComment);
        eventPublisher.publishDeliveryFailed(delivery.getOrder(), delivery, failureComment);

        if (delivery.getOrder() != null) {
            ErpSyncService.syncFailure(delivery.getOrder(),
                    failureCode != null ? failureCode.name() : null, failureComment);
        }

        try { messagingTemplate.convertAndSend("/topic/admin/deliveries", deliveryPayload(delivery, "FAILED")); } catch(Exception ignored){}

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
        transportPort.incrementStat(driverId.toString(), "cancelled");

        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_CANCEL", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "motif", StringUtils.hasText(reason) ? reason : "aucun",
                       "action", "Annulation par le chauffeur"));
        appendHistory(delivery, DeliveryStatus.UNSCHEDULED, driverId.toString(), Role.DRIVER,
                StringUtils.hasText(reason) ? reason : "Driver cancelled, reassigning");

        eventPublisher.publishDeliveryCancelled(delivery.getOrder(), delivery, driverId);

        try { messagingTemplate.convertAndSend("/topic/admin/deliveries", deliveryPayload(delivery, "UNSCHEDULED")); } catch(Exception ignored){}

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

        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
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
        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        
        auditLogService.logAction(principal, "REPORT_INCIDENT", "INCIDENT", targetId,
                java.util.Map.of(
                    "chauffeur", driverName,
                    "type", req.getReportType().name(), 
                    "photos", photoUrls.size(), 
                    "action", "Signalement d'incident pro"
                ));
    }

    // ── Handoff confirmation (Driver B confirms physical receipt) ──────────────

    @Transactional
    public HandoffTokenResponse generateHandoffToken(UUID deliveryId, UUID driverId) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        
        RouteStop stop = routeStopRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> AppException.notFound("No route stop found for this delivery"));

        if (!Boolean.TRUE.equals(stop.getRequiresHandoff())) {
            throw AppException.badRequest("This delivery is not marked for handoff");
        }

        // Generate a 6-character alphanumeric secure token
        String token = java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        stop.setHandoffToken(token);
        stop.setHandoffTokenExpiresAt(expiresAt);
        routeStopRepository.save(stop);

        log.info("HANDOFF_TOKEN_GENERATED deliveryId={} driverId={} token={}", deliveryId, driverId, token);

        return HandoffTokenResponse.builder()
                .token(token)
                .deliveryId(deliveryId.toString())
                .expiresAt(expiresAt)
                .build();
    }

    @Transactional
    public DriverDeliveryResponse confirmHandoff(UUID deliveryId, UUID driverId, String token, UserPrincipal principal) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        RouteStop stop = routeStopRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> AppException.notFound("No route stop found for this delivery"));

        if (!Boolean.TRUE.equals(stop.getRequiresHandoff())) {
            throw AppException.badRequest("This delivery does not require a handoff confirmation");
        }

        if (stop.getHandoffConfirmedAt() != null) {
            // Already confirmed — idempotent return
            return toDriverDeliveryResponse(delivery);
        }

        if (!driverId.equals(stop.getHandoffToDriverId())) {
            throw AppException.forbidden("Only the receiving driver can confirm the handoff");
        }

        // Validate Token
        if (stop.getHandoffToken() == null || !stop.getHandoffToken().equals(token)) {
            throw AppException.badRequest("Invalid handoff token");
        }
        if (stop.getHandoffTokenExpiresAt() != null && stop.getHandoffTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw AppException.badRequest("Handoff token has expired. Please ask the sender to generate a new one.");
        }

        stop.setHandoffConfirmedAt(LocalDateTime.now());
        stop.setRequiresHandoff(false);
        routeStopRepository.save(stop);

        String driverName = (principal != null && principal.getName() != null) ? principal.getName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "HANDOFF_CONFIRMED", "DELIVERY", deliveryId.toString(),
                Map.of("chauffeur", driverName, "client", clientName,
                       "fromDriver", stop.getHandoffFromDriverId() != null ? stop.getHandoffFromDriverId().toString() : "unknown",
                       "action", "Confirmation de remise du colis"));

        appendHistory(delivery, delivery.getStatus(), driverId.toString(), Role.DRIVER,
                "Handoff confirmed — package received from driver " +
                (stop.getHandoffFromDriverId() != null ? stop.getHandoffFromDriverId().toString().substring(0, 8) : "unknown"));

        // Notify admin dashboard
        try {
            messagingTemplate.convertAndSend("/topic/admin/routes", Map.of(
                    "event", "HANDOFF_CONFIRMED",
                    "deliveryId", deliveryId.toString(),
                    "routeId", stop.getRoute().getId().toString(),
                    "driverId", driverId.toString()
            ));
        } catch (Exception ignored) {}

        log.info("HANDOFF_CONFIRMED deliveryId={} fromDriver={} toDriver={}",
                deliveryId, stop.getHandoffFromDriverId(), driverId);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Location update ───────────────────────────────────────────────────────

    @Transactional
    public void updateLocation(UUID driverId, BigDecimal lat, BigDecimal lng) {
        // Store tracking point for active delivery
        List<Delivery> active = deliveryRepo.findActiveForDriver(driverId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            trackingRepo.save(Tracking.builder()
                    .deliveryId(active.get(0).getId())
                    .lat(lat)
                    .lng(lng)
                    .build());
        }

        // Propagate to Driver Service (best-effort)
        transportPort.updateLocation(driverId.toString(), lat.doubleValue(), lng.doubleValue());
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
                appendHistory(delivery, DeliveryStatus.UNSCHEDULED, "SYSTEM", Role.SYSTEM, "Workflow: timeout reset");
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
            appendHistory(delivery, DeliveryStatus.CANCELLED, "SYSTEM", Role.SYSTEM, reason);
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
            appendHistory(delivery, DeliveryStatus.FAILED, "SYSTEM", Role.SYSTEM, reason);
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

    private void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role, String note) {
        historyRepo.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .note(note)
                .build());
    }

    @Transactional
    public DriverDeliveryResponse recordCodCollection(UUID deliveryId, UUID driverId,
                                                      com.asm.delivery.dto.request.CodCollectionRequest req) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));
        if (!driverId.equals(delivery.getDriverId())) {
            throw AppException.forbidden("Not your delivery");
        }
        if (delivery.getOrder() == null || !Boolean.TRUE.equals(delivery.getOrder().getIsCod())) {
            throw AppException.badRequest("This delivery is not a COD order");
        }
        delivery.setCodCollected(req.getCodCollected());
        delivery.setCodAmountCollected(Boolean.TRUE.equals(req.getCodCollected()) ? req.getCodAmountCollected() : null);
        delivery = deliveryRepo.save(delivery);
        return toDriverDeliveryResponse(delivery);
    }

    public DriverDeliveryResponse toDriverDeliveryResponse(Delivery delivery) {
        Order order = delivery.getOrder();
        String erpId = order != null ? order.getErpOrderId() : null;
        String orderRef = (erpId != null && !erpId.isBlank())
                ? erpId
                : (order != null ? order.getId().toString().substring(0, 8).toUpperCase() : null);

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
                .isCod(order != null && Boolean.TRUE.equals(order.getIsCod()))
                .codCollected(delivery.getCodCollected())
                .codAmountCollected(delivery.getCodAmountCollected())
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
                .build();
    }
}
