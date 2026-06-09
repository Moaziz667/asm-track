package com.asm.delivery.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * DEPRECATED. The unified {@link com.asm.delivery.sla.SlaStateService} is now the single SLA engine
 * — window-anchored phases, persisted dedup, one {@code sla.alert} per health transition. The old
 * 20-second four-motif loop ({@code SLA_WAITING/ASSIGNMENT/PICKUP/TRANSIT}) and its in-memory
 * {@code alertedKeys} dedup have been removed.
 *
 * <p>This shell remains only because {@code ExceptionResolutionService} still calls
 * {@link #clearDeliveryAlerts}; re-plan/reassign grace is now owned by
 * {@code SlaStateService.applyReplanGrace}, so this method is intentionally a no-op.
 */
@Service
public class SlaMonitoringService {

    /** No-op — superseded by {@code com.asm.delivery.sla.SlaStateService}. */
    public void clearDeliveryAlerts(UUID deliveryId) {
        // intentionally empty
    }
}
