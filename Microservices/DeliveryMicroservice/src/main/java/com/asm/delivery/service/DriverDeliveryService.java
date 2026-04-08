package com.asm.delivery.service;

import com.asm.delivery.dto.request.ProofOfDeliveryRequest;
import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.odoo.OdooSyncService;
import com.asm.delivery.repository.*;
import com.asm.delivery.storage.MinioStorageService;
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
    private final OdooSyncService                 odooSyncService;
    private final ProofOfDeliveryRepository       podRepo;
    private final TransportPort                   transportPort;
    private final MinioStorageService             minioStorageService;
    private final RouteService                    routeService;
    private final OsrmRoutingService              osrmRoutingService;
    private final AuditLogService                  auditLogService;

    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
    );

    // ── Get available deliveries ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DriverDeliveryResponse> getAvailable(UUID driverId) {
        return deliveryRepo.findAllWaitingWithOrder(DeliveryStatus.WAITING_DRIVER).stream()
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
                && delivery.getStatus() != DeliveryStatus.WAITING_DRIVER) {
            throw AppException.forbidden("Not your delivery");
        }

        return toDriverDeliveryResponse(delivery);
    }

    // ── Accept delivery (atomic) ──────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse accept(UUID deliveryId, UUID driverId) {
        // Check driver doesn't already have an active delivery
        boolean hasActive = deliveryRepo.existsActiveDeliveryForDriver(driverId, ACTIVE_STATUSES);
        if (hasActive) {
            throw AppException.badRequest("You already have an active delivery");
        }

        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (delivery.getStatus() != DeliveryStatus.WAITING_DRIVER) {
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

        // Mark driver unavailable via Driver Service (best-effort)
        transportPort.setAvailability(driverId.toString(), false);

        auditLogService.logAction(null, "DRIVER_ACCEPT", deliveryId.toString(), "Driver " + driverId + " accepted delivery");

        appendHistory(delivery, DeliveryStatus.ASSIGNED, driverId.toString(), Role.DRIVER, "Driver accepted delivery");
        eventPublisher.publishDeliveryAssigned(delivery.getOrder(), delivery, driverId);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Pickup ────────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse pickup(UUID deliveryId, UUID driverId) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        assertStatus(delivery, DeliveryStatus.ASSIGNED, "pickup");

        delivery.setStatus(DeliveryStatus.PICKED_UP);
        delivery.setPickedUpAt(LocalDateTime.now());
        delivery = deliveryRepo.save(delivery);

        appendHistory(delivery, DeliveryStatus.PICKED_UP, driverId.toString(), Role.DRIVER, "Package picked up");
        eventPublisher.publishDeliveryPickedUp(delivery.getOrder(), delivery);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Transit ───────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse transit(UUID deliveryId, UUID driverId, BigDecimal lat, BigDecimal lng) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        assertStatus(delivery, DeliveryStatus.PICKED_UP, "start transit");

        LocalDateTime transitStartedAt = LocalDateTime.now();
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        delivery.setInTransitAt(transitStartedAt);

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

        Order order = delivery.getOrder();
        BigDecimal destinationLat = order != null ? order.getDropoffLat() : null;
        BigDecimal destinationLng = order != null ? order.getDropoffLng() : null;

        var routeSnapshot = osrmRoutingService.computeRoute(
                originLat,
                originLng,
                destinationLat,
                destinationLng,
                transitStartedAt
        );
        if (routeSnapshot.isPresent()) {
            var route = routeSnapshot.get();
            delivery.setRouteGeometry(route.geometry());
            delivery.setRouteDistanceKm(route.distanceKm());
            delivery.setRouteDurationMinutes(route.durationMinutes());
            delivery.setRouteEtaAt(route.etaAt());
            delivery.setTransitSlaMinutesComputed(route.computedTransitSlaMinutes());
            delivery.setRouteProvider(route.provider());
            delivery.setRouteLastComputedAt(LocalDateTime.now());
            } else {
                log.warn("Route snapshot empty for delivery={} originLat={} originLng={} destinationLat={} destinationLng={}",
                    delivery.getId(), originLat, originLng, destinationLat, destinationLng);
        }

        delivery = deliveryRepo.save(delivery);

        String transitNote = routeSnapshot.isPresent()
                ? "Driver started transit. Route ETA calculated."
                : "Driver started transit";
        appendHistory(delivery, DeliveryStatus.IN_TRANSIT, driverId.toString(), Role.DRIVER, transitNote);
        eventPublisher.publishDeliveryInTransit(
                delivery.getOrder(),
                delivery,
                originLat,
                originLng,
                delivery.getRouteDistanceKm(),
                delivery.getRouteDurationMinutes(),
                delivery.getTransitSlaMinutesComputed(),
                delivery.getRouteEtaAt(),
                delivery.getRouteProvider()
        );

        return toDriverDeliveryResponse(delivery);
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse complete(UUID deliveryId, UUID driverId) {
        return complete(deliveryId, driverId, false, null);
    }

    @Transactional
    public DriverDeliveryResponse complete(UUID deliveryId, UUID driverId, boolean isPartial, List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems) {
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

        auditLogService.logAction(null, "DRIVER_COMPLETE", delivery.getId().toString(), 
            String.format("Delivery %s marked as %s by driver %s", delivery.getId(), finalStatus, driverId));

        String message = isPartial ? "Delivery partially completed" : "Delivery completed";
        appendHistory(delivery, finalStatus, driverId.toString(), Role.DRIVER, message);
        routeService.syncStopFromDelivery(delivery.getId(), finalStatus, delivery.getCompletedAt(), message);
        
        // TODO: eventPublisher.publishDeliveryPartiallyCompleted may be needed in the future
        // For now we can use the same event or add conditionally. 
        // We'll publish completed event. Or is there a specific logic in the subscriber?
        eventPublisher.publishDeliveryCompleted(delivery.getOrder(), delivery, driverId);

        // Release driver + increment stat (best-effort)
        transportPort.setAvailability(driverId.toString(), true);
        String stat = isPartial ? "partial" : "delivered";
        transportPort.incrementStat(driverId.toString(), stat);

        // Sync Odoo
        if (delivery.getOrder() != null) {
            if (isPartial) {
                // If there's partial logic in Odoo sync, handle it here. Else sync normally.
                if (normalizedPartialItems != null) {
                    odooSyncService.syncPartialStockUpdate(delivery.getOrder(), normalizedPartialItems);
                } else {
                    odooSyncService.syncStockUpdate(delivery.getOrder());
                }
            } else {
                odooSyncService.syncStockUpdate(delivery.getOrder());
            }
        }

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
    public DriverDeliveryResponse submitPod(UUID deliveryId, UUID driverId, ProofOfDeliveryRequest req) {
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

        return complete(deliveryId, driverId, req.isPartial(), req.getItemsDone());
    }

    // ── Fail ──────────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse fail(UUID deliveryId, UUID driverId, FailureCode failureCode, String failureComment) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        if (delivery.getStatus() != DeliveryStatus.PICKED_UP && delivery.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw AppException.badRequest("Can only fail delivery from PICKED_UP or IN_TRANSIT state");
        }

        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setFailReason(failureComment);
        delivery.setFailureCode(failureCode);
        delivery = deliveryRepo.save(delivery);

        auditLogService.logAction(null, "DRIVER_FAIL", delivery.getId().toString(), 
            String.format("Delivery %s failed by driver %s. Code: %s, Reason: %s", 
            delivery.getId(), driverId, failureCode, failureComment));

        // Release driver + increment stat (best-effort)
        transportPort.setAvailability(driverId.toString(), true);
        transportPort.incrementStat(driverId.toString(), "failed");

        appendHistory(delivery, DeliveryStatus.FAILED, driverId.toString(), Role.DRIVER, failureComment);
        routeService.syncStopFromDelivery(delivery.getId(), DeliveryStatus.FAILED, delivery.getFailedAt(), failureComment);
        eventPublisher.publishDeliveryFailed(delivery.getOrder(), delivery, failureComment);

        if (delivery.getOrder() != null) {
            odooSyncService.syncFailure(delivery.getOrder(),
                    failureCode != null ? failureCode.name() : null, failureComment);
        }

        return toDriverDeliveryResponse(delivery);
    }

    // ── Cancel (driver cancels → back to WAITING_DRIVER) ─────────────────────

    @Transactional
    public DriverDeliveryResponse cancelByDriver(UUID deliveryId, UUID driverId, String reason) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        if (delivery.getStatus() != DeliveryStatus.ASSIGNED) {
            throw AppException.badRequest("Driver can only cancel from ASSIGNED state");
        }

        delivery.setStatus(DeliveryStatus.WAITING_DRIVER);
        delivery.setDriverId(null);
        delivery.setAssignedAt(null);
        delivery = deliveryRepo.save(delivery);

        // Release driver + increment stat (best-effort)
        transportPort.setAvailability(driverId.toString(), true);
        transportPort.incrementStat(driverId.toString(), "cancelled");

        appendHistory(delivery, DeliveryStatus.WAITING_DRIVER, driverId.toString(), Role.DRIVER,
                StringUtils.hasText(reason) ? reason : "Driver cancelled, reassigning");

        eventPublisher.publishDeliveryCancelled(delivery.getOrder(), delivery, driverId);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    @Transactional
    public void report(UUID deliveryId, UUID driverId, ReportType reportType, String description) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        reportRepo.save(DeliveryReport.builder()
                .deliveryId(delivery.getId())
                .driverId(driverId)
                .reportType(reportType)
                .description(description)
                .build());
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
            if (delivery.getStatus() == DeliveryStatus.ASSIGNED && delivery.getDriverId() != null) {
                transportPort.setAvailability(delivery.getDriverId().toString(), true);
                delivery.setStatus(DeliveryStatus.WAITING_DRIVER);
                delivery.setDriverId(null);
                delivery.setAssignedAt(null);
                deliveryRepo.save(delivery);
                appendHistory(delivery, DeliveryStatus.WAITING_DRIVER, "SYSTEM", Role.SYSTEM, "Workflow: timeout reset");
            }
        });
    }

    @Transactional
    public void forceCancel(UUID deliveryId, String reason) {
        deliveryRepo.findByIdWithOrder(deliveryId).ifPresent(delivery -> {
            if (delivery.getDriverId() != null) {
                transportPort.setAvailability(delivery.getDriverId().toString(), true);
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
                transportPort.setAvailability(delivery.getDriverId().toString(), true);
            }
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setFailedAt(LocalDateTime.now());
            delivery.setFailReason(reason);
            deliveryRepo.save(delivery);
            appendHistory(delivery, DeliveryStatus.FAILED, "SYSTEM", Role.SYSTEM, reason);
            routeService.syncStopFromDelivery(delivery.getId(), DeliveryStatus.FAILED, delivery.getFailedAt(), reason);
            eventPublisher.publishDeliveryFailed(delivery.getOrder(), delivery, reason);
        });
    }

    // ── Private ───────────────────────────────────────────────────────────────

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

    public DriverDeliveryResponse toDriverDeliveryResponse(Delivery delivery) {
        Order order = delivery.getOrder();
        return DriverDeliveryResponse.builder()
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
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
                .build();
    }
}
