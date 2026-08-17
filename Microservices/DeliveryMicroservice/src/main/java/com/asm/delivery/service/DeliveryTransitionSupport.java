package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.DeliveryStatusHistory;
import com.asm.delivery.entity.Role;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DeliveryStatusHistoryRepository;
import com.asm.delivery.sla.SlaStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * What every driver action does around the change itself: check the caller may, and record that it
 * happened.
 *
 * <p>These three operations were private helpers of a single large service, and they are the reason
 * that service could not simply be cut in two — {@code appendHistory} was called by nine of its
 * public methods and {@code loadAndAuthorize} by six, spread across every phase of the journey.
 * Duplicating them per phase would have been worse than the original: the ownership check is the one
 * rule keeping a driver out of another driver's delivery, and it must have exactly one definition.
 */
@Component
@RequiredArgsConstructor
public class DeliveryTransitionSupport {

    private final DeliveryRepository deliveryRepo;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final SlaStateService slaStateService;
    private final ObjectMapper objectMapper;
    private final ActionClock actionClock;

    /**
     * Load a delivery and refuse it to anyone but the driver holding it.
     *
     * <p>The single place custody is enforced on the driver path.
     */
    public Delivery loadAndAuthorize(UUID deliveryId, UUID driverId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (!driverId.equals(delivery.getDriverId())) {
            throw AppException.forbidden("Not your delivery");
        }
        return delivery;
    }

    /** Refuse a transition that does not start from the status it requires. */
    public void assertStatus(Delivery delivery, DeliveryStatus expected, String action) {
        if (delivery.getStatus() != expected) {
            throw AppException.badRequest("Cannot " + action + " from status " + delivery.getStatus());
        }
    }

    /**
     * Append one line to the delivery's history, and refresh its SLA.
     *
     * <p>The SLA refresh rides here on purpose: it is the single hook that covers every status
     * transition — accept, pickup, transit, complete, fail, cancel — including the terminal states the
     * periodic tick skips. Recording the change and re-deriving the SLA are one act; separating them
     * is how one of the two gets forgotten.
     */
    public void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role,
                              String eventKey, Map<String, Object> params) {
        String jsonParams = "{}";
        try {
            jsonParams = objectMapper.writeValueAsString(params != null ? params : Map.of());
        } catch (Exception ignored) {
            // A history line with unreadable params is worth more than no history line.
        }

        historyRepo.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                // The moment the driver tapped (X-Client-Timestamp), not the reconnection replay.
                // For a system/admin action there is no header, so ActionClock falls back to now() —
                // which is correct there. This is the timeline the admin reads ("par <livreur>, <date>").
                .changedAt(actionClock.now())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .eventKey(eventKey)
                .eventParams(jsonParams)
                .build());

        slaStateService.refresh(delivery);
    }
}
