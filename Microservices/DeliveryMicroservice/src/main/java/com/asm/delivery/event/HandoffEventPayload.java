package com.asm.delivery.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Real-time payload for handoff lifecycle events (handoff.incoming / outgoing /
 * code_ready / confirmed / cancelled / overdue). Carries enough delivery context
 * for a human-readable, actionable notification on both driver apps and the
 * admin dashboard — flat fields so the web/STOMP and FCM consumers can read them
 * without extra lookups.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandoffEventPayload {
    private String handoffId;
    private String state;

    private String deliveryId;
    private String routeId;
    private String erpOrderId;     // human order ref
    private String clientName;
    private String dropoffAddress;

    private String fromDriverId;
    private String fromDriverName;
    private String toDriverId;
    private String toDriverName;

    private String reason;
}
