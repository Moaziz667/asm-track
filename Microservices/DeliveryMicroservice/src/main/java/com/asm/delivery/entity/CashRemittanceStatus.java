package com.asm.delivery.entity;

/**
 * Where a cash handover has got to.
 *
 * <p>The transitions are one-way and each is performed by a different party, which is what makes the
 * count meaningful:
 *
 * <pre>
 *   DECLARED ──count──▶ RECONCILED   (the figures matched — nothing left to arbitrate)
 *            └─count──▶ DISPUTED ──settle──▶ RECONCILED
 * </pre>
 *
 * <p>The driver declares, somebody else counts, and only a manager settles a gap.
 *
 * <p>This comment used to describe a {@code DECLARED → RECEIVED} step that the code has never taken,
 * and two of the five values below were unreachable — one of them offered as a filter tab on the
 * cash desk that could only ever return nothing. A state machine documented differently from the way
 * it runs is worse than an undocumented one: it is the version a reader will trust.
 */
public enum CashRemittanceStatus {

    /**
     * Never persisted — the field default on a handover that has not been declared yet.
     *
     * <p>Collections attach themselves to a driver, not to a handover, and the handover row is
     * created by the declaration itself. Kept because it is the initial value the entity carries
     * before that save, and because {@code IN_FLIGHT} names it when guarding against a second
     * declaration.
     */
    OPEN,

    /** The driver has said how much he is handing over. Waiting to be counted. */
    DECLARED,

    /**
     * @deprecated Never written. A count either balances — and closes as {@link #RECONCILED} in the
     *             same movement, because a handover that matched has nothing left to arbitrate — or
     *             it does not, and becomes {@link #DISPUTED}. There is no state in between, so
     *             nothing should test for this one or offer it as a filter.
     */
    @Deprecated
    RECEIVED,

    /** Counted and the figures disagree. Waits for a manager. */
    DISPUTED,

    /** Settled — either it balanced on the count, or a manager explained why it did not. */
    RECONCILED
}
