package com.asm.delivery.entity;

/**
 * Outcome of one collection attempt. Derived from the amounts by
 * {@link CashCollection#statusFor}, never chosen by the caller — a status that could disagree with
 * the figures beside it would be the first thing to drift.
 */
public enum CashCollectionStatus {
    /** The instruction exists; the driver has not reported yet. */
    PENDING,
    /** The full expected amount was taken. */
    COLLECTED,
    /** Some of it was taken; the rest is settled outside ASM. */
    PARTIAL,
    /** Nothing was taken. Carries a reason from the failure-reason catalog. */
    REFUSED
}
