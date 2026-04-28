package com.asm.delivery.service;

import com.asm.delivery.dto.response.*;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryQueryService {

    private final DeliveryRepository              deliveryRepo;
    private final TrackingRepository              trackingRepo;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final TransportPort                   transportPort;

    // ── Get delivery (client/driver view) ─────────────────────────────────────

    @Transactional(readOnly = true)
    public DeliveryResponse getDelivery(UUID deliveryId, String requesterId, String requesterRole) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        assertAccessToDelivery(delivery, requesterId, requesterRole);

        return toDeliveryResponse(delivery);
    }

    // ── Tracking ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TrackingPointResponse> getTracking(UUID deliveryId, String requesterId, String requesterRole) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        assertAccessToDelivery(delivery, requesterId, requesterRole);

        return trackingRepo.findByDeliveryIdOrderByTimestampAsc(deliveryId).stream()
                .map(t -> new TrackingPointResponse(t.getLat(), t.getLng(), t.getTimestamp()))
                .collect(Collectors.toList());
    }

    // ── Status history ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StatusHistoryResponse> getHistory(UUID deliveryId, String requesterId, String requesterRole) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        assertAccessToDelivery(delivery, requesterId, requesterRole);

        return historyRepo.findByDeliveryIdOrderByChangedAtAsc(deliveryId).stream()
            .map(h -> {
                String actorDisplay = resolveActorName(h.getChangedBy(), h.getChangedByRole());
                return StatusHistoryResponse.builder()
                    .id(h.getId() != null ? h.getId().toString() : null)
                    .status(h.getStatus().name())
                    .actor(actorDisplay)
                    .timestamp(h.getChangedAt())
                    .changedBy(actorDisplay)
                    .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                    .note(h.getNote())
                    .changedAt(h.getChangedAt())
                    .build();
            })
                .collect(Collectors.toList());
    }

    private String resolveActorName(String changedBy, com.asm.delivery.entity.Role role) {
        if (changedBy == null) return null;
        if ("SYSTEM".equalsIgnoreCase(changedBy)) return "Système";
        try {
            UUID.fromString(changedBy);
            if (role == com.asm.delivery.entity.Role.DRIVER) {
                DriverDTO driver = transportPort.getDriver(changedBy);
                if (driver != null && driver.getName() != null) return driver.getName();
            }
            if (role == com.asm.delivery.entity.Role.DISPATCHER || role == com.asm.delivery.entity.Role.ADMIN) return "Dispatching";
            return changedBy.substring(0, 8).toUpperCase();
        } catch (IllegalArgumentException e) {
            return changedBy;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertAccessToDelivery(Delivery delivery, String requesterId, String requesterRole) {
        if ("DRIVER".equals(requesterRole)) {
            if (delivery.getDriverId() != null && !delivery.getDriverId().toString().equals(requesterId)
                    && delivery.getStatus() != DeliveryStatus.UNSCHEDULED) {
                throw AppException.forbidden("Not your delivery");
            }
        } else if ("CLIENT".equals(requesterRole)) {
            Order order = delivery.getOrder();
            if (OrderSource.APP.equals(order.getSource()) && !requesterId.equals(order.getClientId())) {
                throw AppException.forbidden("Not your delivery");
            }
        }
        // DISPATCHER / ADMIN — no restriction
    }

    public DeliveryResponse toDeliveryResponse(Delivery delivery) {
        String driverName  = null;
        String driverPhone = null;

        if (delivery.getDriverId() != null) {
            DriverDTO driver = transportPort.getDriver(delivery.getDriverId().toString());
            if (driver != null) {
                driverName  = driver.getName();
                driverPhone = driver.getPhone();
            }
        }

        return DeliveryResponse.builder()
                .id(delivery.getId())
                .orderId(delivery.getOrder() != null ? delivery.getOrder().getId() : null)
                .driverId(delivery.getDriverId())
                .driverName(driverName)
                .driverPhone(driverPhone)
                .status(delivery.getStatus().name())
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
