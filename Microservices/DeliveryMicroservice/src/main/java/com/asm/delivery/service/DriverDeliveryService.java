package com.asm.delivery.service;

import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
    private final DriverRepository                driverRepo;
    private final EventPublisher                  eventPublisher;

        private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
        );

    // ── Get available deliveries ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DriverDeliveryResponse> getAvailable(UUID driverId) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> new AppException(org.springframework.http.HttpStatus.NOT_FOUND, "Driver not found"));

        return deliveryRepo.findAllWaitingWithOrder(DeliveryStatus.WAITING_DRIVER).stream()
                .filter(delivery -> delivery.getOrder().getDropoffCity() != null &&
                        delivery.getOrder().getDropoffCity().equalsIgnoreCase(driver.getCity()))
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
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        // Check driver doesn't already have an active delivery
        boolean hasActive = deliveryRepo.existsActiveDeliveryForDriver(driverId, ACTIVE_STATUSES);
        if (hasActive) {
            throw AppException.badRequest("You already have an active delivery");
        }

        // Verify delivery exists
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (delivery.getStatus() != DeliveryStatus.WAITING_DRIVER) {
            throw AppException.conflict("Delivery is no longer available");
        }

        String driverCity = driver.getCity();
        String dropoffCity = delivery.getOrder() != null ? delivery.getOrder().getDropoffCity() : null;
        if (driverCity == null || dropoffCity == null || !dropoffCity.equalsIgnoreCase(driverCity)) {
            throw AppException.forbidden("Delivery is outside your city");
        }

        // Atomic UPDATE — 0 rows = race condition
        int updated = deliveryRepo.atomicAccept(deliveryId, driverId);
        if (updated == 0) {
            throw AppException.conflict("Delivery was just taken by another driver");
        }

        // Reload after update
        delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found after accept"));

        if (!Boolean.FALSE.equals(driver.getAvailable())) {
            driver.setAvailable(false);
            driverRepo.save(driver);
        }

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

        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        delivery.setInTransitAt(LocalDateTime.now());
        delivery = deliveryRepo.save(delivery);

        appendHistory(delivery, DeliveryStatus.IN_TRANSIT, driverId.toString(), Role.DRIVER, "Driver started transit");
        eventPublisher.publishDeliveryInTransit(delivery.getOrder(), delivery, lat, lng);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse complete(UUID deliveryId, UUID driverId) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);
        assertStatus(delivery, DeliveryStatus.IN_TRANSIT, "complete");

        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setCompletedAt(LocalDateTime.now());
        delivery = deliveryRepo.save(delivery);

        appendHistory(delivery, DeliveryStatus.DELIVERED, driverId.toString(), Role.DRIVER, "Delivery completed");
        eventPublisher.publishDeliveryCompleted(delivery.getOrder(), delivery, driverId);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Fail ──────────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse fail(UUID deliveryId, UUID driverId, String reason) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        if (delivery.getStatus() != DeliveryStatus.PICKED_UP && delivery.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw AppException.badRequest("Can only fail delivery from PICKED_UP or IN_TRANSIT state");
        }

        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setFailReason(reason);
        delivery = deliveryRepo.save(delivery);

        // Release driver availability
        releaseDriver(driverId);

        appendHistory(delivery, DeliveryStatus.FAILED, driverId.toString(), Role.DRIVER, reason);
        eventPublisher.publishDeliveryFailed(delivery.getOrder(), delivery, reason);

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

        // Release driver
        releaseDriver(driverId);

        appendHistory(delivery, DeliveryStatus.WAITING_DRIVER, driverId.toString(), Role.DRIVER,
                StringUtils.hasText(reason) ? reason : "Driver cancelled, reassigning");

        // Publish cancelled event
        eventPublisher.publishDeliveryCancelled(delivery.getOrder(), delivery, driverId);

        return toDriverDeliveryResponse(delivery);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    @Transactional
    public void report(UUID deliveryId, UUID driverId, ReportType reportType, String description) {
        Delivery delivery = loadAndAuthorize(deliveryId, driverId);

        DeliveryReport report = DeliveryReport.builder()
                .deliveryId(delivery.getId())
                .driverId(driverId)
                .reportType(reportType)
                .description(description)
                .build();
        reportRepo.save(report);
    }

    // ── Location update (stores tracking point + updates driver) ─────────────

    @Transactional
    public void updateLocation(UUID driverId, BigDecimal lat, BigDecimal lng) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        driver.setCurrentLat(lat);
        driver.setCurrentLng(lng);
        driver.setLastLocationAt(LocalDateTime.now());
        driverRepo.save(driver);

        // Store tracking point for active delivery
        List<Delivery> active = deliveryRepo.findActiveForDriver(driverId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            trackingRepo.save(Tracking.builder()
                    .deliveryId(active.get(0).getId())
                    .lat(lat)
                    .lng(lng)
                    .build());
        }
    }

    // ── Workflow service integration ──────────────────────────────────────────

    @Transactional
    public void resetToWaiting(UUID deliveryId) {
        deliveryRepo.findByIdWithOrder(deliveryId).ifPresent(delivery -> {
            if (delivery.getStatus() == DeliveryStatus.ASSIGNED && delivery.getDriverId() != null) {
                releaseDriver(delivery.getDriverId());
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
            if (delivery.getDriverId() != null) releaseDriver(delivery.getDriverId());
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
            if (delivery.getDriverId() != null) releaseDriver(delivery.getDriverId());
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setFailedAt(LocalDateTime.now());
            delivery.setFailReason(reason);
            deliveryRepo.save(delivery);
            appendHistory(delivery, DeliveryStatus.FAILED, "SYSTEM", Role.SYSTEM, reason);
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

    private void releaseDriver(UUID driverId) {
        driverRepo.findById(driverId).ifPresent(driver -> {
            driver.setAvailable(true);
            driverRepo.save(driver);
        });
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
                .paymentType(order != null ? order.getPaymentType().name() : null)
                .amountToCollect(order != null ? order.getAmountToCollect() : null)
                .currency(order != null ? order.getCurrency() : null)
                .items(order != null ? order.getItems() : null)
                .totalQuantity(order != null ? order.getTotalQuantity() : null)
                .priority(order != null ? order.getPriority().name() : null)
                .scheduledAt(order != null ? order.getScheduledAt() : null)
                .assignedAt(delivery.getAssignedAt())
                .pickedUpAt(delivery.getPickedUpAt())
                .inTransitAt(delivery.getInTransitAt())
                .completedAt(delivery.getCompletedAt())
                .failedAt(delivery.getFailedAt())
                .cancelledAt(delivery.getCancelledAt())
                .failReason(delivery.getFailReason())
                .cancelReason(delivery.getCancelReason())
                .createdAt(delivery.getCreatedAt())
                .build();
    }
}
