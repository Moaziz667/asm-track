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

    @Transactional
    public SlaTimelineResponse build(UUID deliveryId) {
        Delivery d = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + deliveryId));

        // Ensure state exists (lazily compute on first read for pre-existing deliveries).
        SlaState state = slaStateRepository.findByDeliveryId(deliveryId).orElse(null);
        if (state == null) {
            slaStateService.refresh(d);
            state = slaStateRepository.findByDeliveryId(deliveryId).orElse(null);
        }

        SlaTimelineResponse.Current current = state == null ? null : new SlaTimelineResponse.Current(
                state.getPhase() != null ? state.getPhase().name() : null,
                state.getHealth() != null ? state.getHealth().name() : null,
                state.getDueAt() != null ? state.getDueAt().toString() : null,
                state.getLateMinutes(),
                state.isAttributableToDriver(),
                state.getReasonKey(),
                state.getReasonParams());

        List<SlaTimelineResponse.Event> timeline = historyRepository
                .findByDeliveryIdOrderByChangedAtAsc(deliveryId).stream()
                .map(h -> new SlaTimelineResponse.Event(
                        h.getChangedAt() != null ? h.getChangedAt().toString() : null,
                        h.getStatus() != null ? h.getStatus().name() : null,
                        h.getEventKey(),
                        h.getEventParams()))
                .toList();

        return new SlaTimelineResponse(current, timeline, buildContext(d));
    }

    private SlaTimelineResponse.Context buildContext(Delivery d) {
        String failureCode = d.getFailureCode() != null ? d.getFailureCode().name() : null;
        String failReason = d.getFailReason();

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
        return new SlaTimelineResponse.Context(failureCode, failReason, direction, linkedId, linkedBl);
    }

    private List<Delivery> siblings(Delivery d) {
        if (d.getOrder() == null) return List.of();
        return deliveryRepository.findAllByOrderIdWithOrder(d.getOrder().getId()).stream()
                .filter(s -> !s.getId().equals(d.getId()))
                .toList();
    }
}
