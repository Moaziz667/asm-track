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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryQueryService {

    private final DeliveryRepository              deliveryRepo;
    private final TrackingRepository              trackingRepo;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final TransportPort                   transportPort;
    private final com.asm.delivery.web.ActorNameResolver actorNameResolver;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private DeliveryQueryService self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy DeliveryQueryService self) {
        this.self = self;
    }

    // ── Get delivery (client/driver view) ─────────────────────────────────────

    public DeliveryResponse getDelivery(UUID deliveryId, String requesterId, String requesterRole) {
        Delivery delivery = self.doGetDelivery(deliveryId, requesterId, requesterRole);

        DriverDTO driver = null;
        if (delivery.getDriverId() != null) {
            driver = transportPort.getDriver(delivery.getDriverId().toString());
        }

        return toDeliveryResponse(delivery, driver);
    }

    @Transactional(readOnly = true)
    public Delivery doGetDelivery(UUID deliveryId, String requesterId, String requesterRole) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        assertAccessToDelivery(delivery, requesterId, requesterRole);
        return delivery;
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

    public List<StatusHistoryResponse> getHistory(UUID deliveryId, String requesterId, String requesterRole) {
        List<DeliveryStatusHistory> history = self.doGetHistory(deliveryId, requesterId, requesterRole);

        // Resolve every actor (driver + admin/dispatcher) to a real name in at most two remote calls.
        Map<String, String> actorNames = actorNameResolver.prefetch(history);

        return history.stream()
                .map(h -> {
                    String actorDisplay = actorNameResolver.resolve(h.getChangedBy(), h.getChangedByRole(), actorNames);
                    return StatusHistoryResponse.builder()
                            .id(h.getId() != null ? h.getId().toString() : null)
                            .status(h.getStatus().name())
                            .actor(actorDisplay)
                            .timestamp(h.getChangedAt())
                            .changedBy(actorDisplay)
                            .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                            .eventKey(h.getEventKey())
                            .eventParams(deserializeEventParams(h.getEventParams()))
                            .changedAt(h.getChangedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DeliveryStatusHistory> doGetHistory(UUID deliveryId, String requesterId, String requesterRole) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        assertAccessToDelivery(delivery, requesterId, requesterRole);

        return historyRepo.findByDeliveryIdOrderByChangedAtAsc(deliveryId);
    }

    private Map<String, Object> deserializeEventParams(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertAccessToDelivery(Delivery delivery, String requesterId, String requesterRole) {
        if ("DRIVER".equals(requesterRole)) {
            if (delivery.getDriverId() != null && !delivery.getDriverId().toString().equals(requesterId)
                    && delivery.getStatus() != DeliveryStatus.UNSCHEDULED) {
                throw AppException.forbidden("Not your delivery");
            }
        }
        // DISPATCHER / ADMIN — no restriction. (Legacy CLIENT/APP self-service path removed: all
        // orders are ERP-sourced now.)
    }

    public DeliveryResponse toDeliveryResponse(Delivery delivery, DriverDTO driver) {
        String driverName  = driver != null ? driver.getName() : null;
        String driverPhone = driver != null ? driver.getPhone() : null;

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
