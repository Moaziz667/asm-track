package com.asm.delivery.entity;

/**
 * Where a cash handover has got to.
 *
 * <p>The transitions are one-way and each is performed by a different party, which is what makes the
 * count meaningful: {@code OPEN → DECLARED} is the driver, {@code DECLARED → RECEIVED} is whoever
 * counts at the depot, and settling a {@code DISPUTED} handover is a manager.
 */
public enum CashRemittanceStatus {
    /** Collections are accumulating; the driver is still out. */
    OPEN,
    /** The driver has said how much he is handing over. Waiting to be counted. */
    DECLARED,
    /** Counted at the depot. Terminal when the count matched. */
    RECEIVED,
    /** Counted and the figures disagree. Waits for a manager. */
    DISPUTED,
    /** Settled — either it balanced, or a manager explained why it did not. */
    RECONCILED
}
