package com.asm.delivery.sla;

/**
 * Where a delivery sits on its journey — the single "phase" axis the whole platform reads.
 * Live phases carry a {@link SlaHealth}; terminal phases are resolved outcomes.
 */
public enum SlaPhase {
    // ── Live phases (have a dueAt + health) ───────────────────────────────────
    PLANNING,     // UNSCHEDULED — must be put on a route by its scheduled day
    ASSIGNMENT,   // SCHEDULED  — must be picked up in time for the first stop window
    DEPARTURE,    // PICKED_UP  — must leave the depot within the window
    DELIVERY,     // IN_TRANSIT — must arrive within the delivery window
    HANDOFF,      // custody transfer in progress (branch)

    // ── Terminal outcomes (resolved, no live clock) ───────────────────────────
    DELIVERED,
    PARTIAL,
    FAILED,
    CANCELLED
}
