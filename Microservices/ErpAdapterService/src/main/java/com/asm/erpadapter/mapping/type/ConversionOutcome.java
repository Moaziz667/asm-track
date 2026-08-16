package com.asm.erpadapter.mapping.type;

/**
 * What came back from reading one mapped field.
 *
 * <p>Three states, because collapsing them into {@code null} destroyed the only information the
 * integrator needed. A blank cell used to mean "the ERP field is empty", "the value could not be
 * read", and "the path is wrong" indistinguishably — so a mapping pointed at a date-only field
 * produced nothing, looked exactly like an empty ERP, and was found months later on a missed SLA.
 *
 * <p>{@link State#UNREADABLE} carries the reason so the preview can print it and the import can count
 * it. It never carries a value: a conversion that failed has nothing honest to offer.
 */
public record ConversionOutcome(State state, Object value, String reason) {

    public enum State {
        /** Present in the ERP and converted. */
        VALUE,
        /** Genuinely empty at the source — Odoo's {@code false}, a blank string, a missing key. */
        EMPTY,
        /** Present, but no honest conversion exists for it. */
        UNREADABLE
    }

    private static final ConversionOutcome EMPTY = new ConversionOutcome(State.EMPTY, null, null);

    public static ConversionOutcome of(Object value) {
        return new ConversionOutcome(State.VALUE, value, null);
    }

    public static ConversionOutcome empty() {
        return EMPTY;
    }

    public static ConversionOutcome unreadable(String reason) {
        return new ConversionOutcome(State.UNREADABLE, null, reason);
    }

    public boolean isValue()      { return state == State.VALUE; }
    public boolean isEmpty()      { return state == State.EMPTY; }
    public boolean isUnreadable() { return state == State.UNREADABLE; }

    /**
     * The value for callers that cannot express the three states yet.
     *
     * <p>Every use is a place where an unreadable value still degrades to a blank. They are worth
     * finding: this method exists to make them greppable rather than invisible.
     */
    public Object valueOrNull() {
        return state == State.VALUE ? value : null;
    }
}
