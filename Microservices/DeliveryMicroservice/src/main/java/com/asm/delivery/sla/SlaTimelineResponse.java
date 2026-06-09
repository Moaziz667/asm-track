package com.asm.delivery.sla;

import java.util.List;
import java.util.Map;

/**
 * The full delivery story for the frontend SLA timeline component. {@code current} drives the
 * headline (phase + health), {@code timeline} the stepper / per-phase expanded events, and
 * {@code context} the driver-side detail (failure motif, backorder link).
 */
public record SlaTimelineResponse(Current current, List<Event> timeline, Context context) {

    public record Current(String phase, String health, String dueAt, Integer lateMinutes,
                          boolean attributableToDriver, String reasonKey,
                          Map<String, String> reasonParams) {}

    /**
     * One lifecycle event. {@code params} is the stored JSON string (driver names, route, reason…);
     * {@code actor} is the human who did it (resolved driver name / Admin / System / Client) and
     * {@code actorRole} the role for icon/label.
     */
    public record Event(String at, String status, String eventKey, String params,
                        String actor, String actorRole) {}

    public record Context(String failureCode, String failReason,
                          String backorderDirection, String backorderDeliveryId, String backorderBlNumber) {}
}
