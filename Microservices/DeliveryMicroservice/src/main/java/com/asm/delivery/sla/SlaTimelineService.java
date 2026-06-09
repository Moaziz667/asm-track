package com.asm.delivery.sla;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DeliveryStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Assembles the unified SLA timeline payload: the current {@link SlaState} headline + the raw
 * lifecycle events + driver context (failure motif, backorder cross-link). One read, one shape,
 * consumed identically by the per-delivery page, dispatch desk and route detail.
 */
@Service
@RequiredArgsConstructor
public class SlaTimelineService {

    private final SlaStateRepository slaStateRepository;
    private final SlaStateService slaStateService;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository historyRepository;
    private final com.asm.delivery.transport.TransportPort transportPort;
    private final com.asm.delivery.repository.ProofOfDeliveryRepository proofOfDeliveryRepository;

    @Transactional
    public SlaTimelineResponse build(UUID deliveryId) {
        Delivery d = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + deliveryId));

        // Always recompute on read: this single-delivery refresh keeps the headline truthful and
        // self-heals any stale terminal verdict (e.g. a late delivery whose state was computed
        // before its stop's completedAt was stamped). Terminal health isn't alertable, so no
        // spurious alert fires. Cheap — one evaluation, not the bulk reconcile loop.
        slaStateService.refresh(d);
        SlaState state = slaStateRepository.findByDeliveryId(deliveryId).orElse(null);

        SlaTimelineResponse.Current current = state == null ? null : new SlaTimelineResponse.Current(
                state.getPhase() != null ? state.getPhase().name() : null,
                state.getHealth() != null ? state.getHealth().name() : null,
                state.getDueAt() != null ? state.getDueAt().toString() : null,
                state.getLateMinutes(),
                state.isAttributableToDriver(),
                state.getReasonKey(),
                state.getReasonParams(),
                state.getPhaseHealth());

        var rows = historyRepository.findByDeliveryIdOrderByChangedAtAsc(deliveryId);
        java.util.Map<String, String> driverNames = resolveDriverNames(rows);
        String source = d.getOrder() != null && d.getOrder().getSource() != null ? d.getOrder().getSource().name() : "";
        boolean hasCreatedRow = rows.stream().anyMatch(h -> "DELIVERY_CREATED".equals(h.getEventKey()));

        List<SlaTimelineResponse.Event> timeline = new java.util.ArrayList<>();
        // Only synthesize the "imported" event when the real creation row is absent (older deliveries).
        if (!hasCreatedRow) timeline.add(importedEvent(d));
        for (com.asm.delivery.entity.DeliveryStatusHistory h : rows) {
            String role = h.getChangedByRole() != null ? h.getChangedByRole().name() : null;
            // For driver actions changedBy is a driver UUID — show the resolved name instead.
            String actor = "DRIVER".equals(role) ? driverNames.get(h.getChangedBy()) : null;
            String eventKey = h.getEventKey();
            String params = h.getEventParams();
            // Promote the creation row to the richer "imported · source" line (no duplicate).
            if ("DELIVERY_CREATED".equals(eventKey)) {
                eventKey = "DELIVERY_IMPORTED";
                params = "{\"source\":\"" + source + "\"}";
            }
            timeline.add(new SlaTimelineResponse.Event(
                    h.getChangedAt() != null ? h.getChangedAt().toString() : null,
                    h.getStatus() != null ? h.getStatus().name() : null,
                    eventKey, params, actor, role));
        }

        return new SlaTimelineResponse(current, timeline, buildContext(d));
    }

    /** Batch-resolve driver UUIDs (the actor of driver events) to names for a readable audit trail. */
    private java.util.Map<String, String> resolveDriverNames(List<com.asm.delivery.entity.DeliveryStatusHistory> rows) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        rows.stream()
                .filter(h -> h.getChangedByRole() == com.asm.delivery.entity.Role.DRIVER && h.getChangedBy() != null)
                .map(com.asm.delivery.entity.DeliveryStatusHistory::getChangedBy)
                .distinct()
                .forEach(id -> {
                    try {
                        var dto = transportPort.getDriver(id);
                        if (dto != null && dto.getName() != null) names.put(id, dto.getName());
                    } catch (Exception ignored) { /* fall back to role label on the frontend */ }
                });
        return names;
    }

    /** Synthetic "order entered the system" event, derived from createdAt + the order source. */
    private SlaTimelineResponse.Event importedEvent(Delivery d) {
        String source = d.getOrder() != null && d.getOrder().getSource() != null
                ? d.getOrder().getSource().name() : "";
        String role = "ADMIN".equalsIgnoreCase(source) ? "ADMIN" : "SYSTEM";
        return new SlaTimelineResponse.Event(
                d.getCreatedAt() != null ? d.getCreatedAt().toString() : null,
                "UNSCHEDULED", "DELIVERY_IMPORTED",
                "{\"source\":\"" + source + "\"}", null, role);
    }

    private SlaTimelineResponse.Context buildContext(Delivery d) {
        String failureCode = d.getFailureCode() != null ? d.getFailureCode().name() : null;
        String failReason = d.getFailReason();

        // Driver's proof-of-delivery note (handover comment), if captured.
        String podComment = proofOfDeliveryRepository.findByDeliveryId(d.getId())
                .map(com.asm.delivery.entity.ProofOfDelivery::getComment)
                .filter(c -> c != null && !c.isBlank())
                .orElse(null);

        // Per-item outcomes the dispatcher needs to see: only the items NOT delivered cleanly.
        List<SlaTimelineResponse.ItemOutcome> itemOutcomes = java.util.List.of();
        if (d.getOrder() != null && d.getOrder().getItems() != null) {
            itemOutcomes = d.getOrder().getItems().stream()
                    .filter(it -> it.getOutcome() != null && !"DELIVERED".equalsIgnoreCase(it.getOutcome()))
                    .map(it -> new SlaTimelineResponse.ItemOutcome(
                            it.getName(), it.getOutcome(), it.getReason(), it.getComment()))
                    .toList();
        }

        String direction = null, linkedId = null, linkedBl = null;
        if (d.getOdooBackorderId() != null) {
            // This shipment IS a backorder — link back to the original shipment of the same order.
            direction = "child";
            Delivery parent = siblings(d).stream()
                    .filter(s -> s.getOdooBackorderId() == null)
                    .findFirst().orElse(null);
            if (parent != null) { linkedId = parent.getId().toString(); linkedBl = parent.getBlNumber(); }
        } else {
            // This is an original shipment — surface its backorder child, if one exists.
            Delivery child = siblings(d).stream()
                    .filter(s -> s.getOdooBackorderId() != null)
                    .findFirst().orElse(null);
            if (child != null) { direction = "parent"; linkedId = child.getId().toString(); linkedBl = child.getBlNumber(); }
        }
        return new SlaTimelineResponse.Context(failureCode, failReason, direction, linkedId, linkedBl,
                podComment, itemOutcomes);
    }

    private List<Delivery> siblings(Delivery d) {
        if (d.getOrder() == null) return List.of();
        return deliveryRepository.findAllByOrderIdWithOrder(d.getOrder().getId()).stream()
                .filter(s -> !s.getId().equals(d.getId()))
                .toList();
    }
}
