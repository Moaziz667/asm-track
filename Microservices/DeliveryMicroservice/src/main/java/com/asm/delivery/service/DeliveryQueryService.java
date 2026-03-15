package com.asm.delivery.service;

import com.asm.delivery.dto.response.*;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only queries for the client-facing delivery + tracking + history endpoints.
 * GET /api/deliveries/{id}
 * GET /api/deliveries/{id}/tracking
 * GET /api/deliveries/{id}/history
 */
@Service
@RequiredArgsConstructor
public class DeliveryQueryService {

    private final DeliveryRepository              deliveryRepo;
    private final TrackingRepository              trackingRepo;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final DriverRepository                driverRepo;

    // ── Get delivery (client view) ────────────────────────────────────────────

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
                .map(h -> new StatusHistoryResponse(h.getStatus(), h.getChangedBy(),
                        h.getChangedByRole(), h.getNote(), h.getChangedAt()))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertAccessToDelivery(Delivery delivery, String requesterId, String requesterRole) {
        if ("DRIVER".equals(requesterRole)) {
            // Driver can see their deliveries or any WAITING_DRIVER delivery
            if (delivery.getDriverId() != null && !delivery.getDriverId().toString().equals(requesterId)
                    && !"WAITING_DRIVER".equals(delivery.getStatus())) {
                throw AppException.forbidden("Not your delivery");
            }
        } else if ("CLIENT".equals(requesterRole)) {
            Order order = delivery.getOrder();
            if ("APP".equals(order.getSource()) && !requesterId.equals(order.getClientId())) {
                throw AppException.forbidden("Not your delivery");
            }
        }
    }

    public DeliveryResponse toDeliveryResponse(Delivery delivery) {
        String driverName  = null;
        String driverPhone = null;
        if (delivery.getDriverId() != null) {
            driverRepo.findById(delivery.getDriverId()).ifPresent(d -> {
                // we set these using a builder later — using a holder
            });
            // Use a separate lookup to enrich with driver info
            var driverOpt = driverRepo.findById(delivery.getDriverId());
            if (driverOpt.isPresent()) {
                driverName  = driverOpt.get().getName();
                driverPhone = driverOpt.get().getPhone();
            }
        }

        return DeliveryResponse.builder()
                .id(delivery.getId())
                .orderId(delivery.getOrder() != null ? delivery.getOrder().getId() : null)
                .driverId(delivery.getDriverId())
                .driverName(driverName)
                .driverPhone(driverPhone)
                .status(delivery.getStatus())
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
