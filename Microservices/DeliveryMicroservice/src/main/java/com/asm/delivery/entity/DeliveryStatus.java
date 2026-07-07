package com.asm.delivery.entity;

public enum DeliveryStatus {
    UNSCHEDULED,
    SCHEDULED,
    PICKED_UP,
    IN_TRANSIT,
    /**
     * Physically picked up but reassigned in-field to another driver: a custody handoff is open and
     * not yet confirmed. A lateral state reached from PICKED_UP/IN_TRANSIT that resolves to PICKED_UP
     * once the receiver confirms (or reverts to the sender on expiry) — never a rewind to SCHEDULED,
     * the parcel is in someone's hands the whole time. Field-mastered for ERP.
     */
    AWAITING_HANDOFF,
    DELIVERED,
    PARTIALLY_DELIVERED,
    FAILED,
    CANCELLED
}
