package com.asm.delivery.service;

import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.dto.response.ProofOfDeliveryResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.repository.*;
import com.asm.delivery.storage.MediaUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the view a driver's app receives for one delivery.
 *
 * <p>Extracted because it was called from eleven places across what used to be a single 1192-line
 * service — every method that changes a delivery ends by returning its new state. Splitting that
 * service by phase would otherwise have meant either duplicating this, or leaving the phases coupled
 * to each other through it.
 *
 * <p>It reads: the active route stop (handoff state), the RMA lines for a return collection, the
 * proof of delivery once terminal, and the status history. Read-only by construction — it takes an
 * already-loaded {@link Delivery} and never writes.
 */
@Component
@RequiredArgsConstructor
public class DriverDeliveryMapper {

    private final RouteStopRepository routeStopRepository;
    private final ProofOfDeliveryRepository podRepo;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final MediaUrlResolver mediaUrlResolver;
    private final RmaService rmaService;
    private final ObjectMapper objectMapper;

    public DriverDeliveryResponse toDriverDeliveryResponse(Delivery delivery) {
        // Look up handoff info from the active route stop
        RouteStop activeStop = routeStopRepository.findActiveByDeliveryId(delivery.getId()).orElse(null);
        boolean requiresHandoff = activeStop != null && Boolean.TRUE.equals(activeStop.getRequiresHandoff());

        Order order = delivery.getOrder();
        String orderRef = order != null ? order.resolveRef() : null;

        // ADR-033 — a return collection's manifest shows the RMA lines (what to collect), not the shared
        // order's original ordered quantities.
        boolean isReturnPickupManifest = delivery.getKind() == com.asm.delivery.entity.DeliveryKind.RETURN_PICKUP;
        List<com.asm.delivery.entity.OrderItem> manifestItems = isReturnPickupManifest
                ? rmaService.collectionItems(delivery.getRmaId())
                : (order != null ? order.getItems() : null);

        // Fetch POD when delivery is terminal (completed/partial/failed with photos)
        ProofOfDeliveryResponse podResponse = null;
        if (delivery.getStatus() == DeliveryStatus.DELIVERED
                || delivery.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED) {
            ProofOfDelivery pod = podRepo.findByDeliveryId(delivery.getId()).orElse(null);
            if (pod != null) {
                podResponse = ProofOfDeliveryResponse.builder()
                        .id(pod.getId())
                        .deliveryId(pod.getDeliveryId())
                        .photoUrl(mediaUrlResolver.toPublicUrl(pod.getPhotoUrl()))
                        .signatureUrl(mediaUrlResolver.toPublicUrl(pod.getSignatureUrl()))
                        .bonLivraisonPhotoUrl(mediaUrlResolver.toPublicUrl(pod.getBonLivraisonPhotoUrl()))
                        .comment(pod.getComment())
                        .collectedAt(pod.getCollectedAt())
                        .lat(pod.getLat())
                        .lng(pod.getLng())
                        .build();
            }
        }

        // Fetch status history timeline
        List<StatusHistoryResponse> historyItems = historyRepo
                .findByDeliveryIdOrderByChangedAtAsc(delivery.getId())
                .stream()
                .map(h -> StatusHistoryResponse.builder()
                        .id(h.getId().toString())
                        .status(h.getStatus() != null ? h.getStatus().name() : null)
                        .eventKey(h.getEventKey())
                        .eventParams(parseEventParams(h.getEventParams()))
                        .changedAt(h.getChangedAt())
                        .changedBy(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                        .build())
                .toList();

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
                .codRequired(order != null && Boolean.TRUE.equals(order.getCodRequired()))
                .codAmount(order != null && Boolean.TRUE.equals(order.getCodRequired())
                        ? order.getCodAmount() : null)
                .kind(delivery.getKind() != null ? delivery.getKind().name() : "FORWARD")
                .rmaNumber(isReturnPickupManifest ? rmaService.collectionRef(delivery.getRmaId()) : null)
                .items(manifestItems)
                .totalQuantity(isReturnPickupManifest
                        ? manifestItems.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum()
                        : (order != null ? order.getTotalQuantity() : null))
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
                .proofOfDelivery(podResponse)
                .statusHistory(historyItems)
                .build();
    }

    /** Best-effort parse of the JSON eventParams blob; null on absent/malformed (never fails the read). */
    private Map<String, Object> parseEventParams(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
