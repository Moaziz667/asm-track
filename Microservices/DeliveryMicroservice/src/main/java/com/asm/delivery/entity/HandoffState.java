package com.asm.delivery.entity;

/**
 * Lifecycle of a custody transfer (handoff) of a physically picked-up parcel
 * from one driver to another.
 *
 * <pre>
 *   REQUESTED ──(sender generates code)──▶ IN_PROGRESS ──(receiver confirms)──▶ CONFIRMED
 *       │                                      │
 *       └──────────(SLA timeout / admin)───────┴──▶ EXPIRED
 *       └──────────(admin / replan)───────────────▶ CANCELLED
 * </pre>
 *
 * CONFIRMED, EXPIRED and CANCELLED are terminal.
 */
public enum HandoffState {
    /** Created by a reassignment/transfer of an in-field parcel; both drivers notified. */
    REQUESTED,
    /** Sender generated a one-time code; awaiting the receiver's scan. */
    IN_PROGRESS,
    /** Receiver confirmed physical receipt — custody transferred. */
    CONFIRMED,
    /** Timed out (or too many bad code attempts) before confirmation. */
    EXPIRED,
    /** Aborted by an admin / superseded by a replan. */
    CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == EXPIRED || this == CANCELLED;
    }
}
