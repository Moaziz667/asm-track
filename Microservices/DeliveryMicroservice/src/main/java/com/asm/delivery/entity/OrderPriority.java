package com.asm.delivery.entity;

/**
 * How urgent a delivery is — an indication for the dispatcher, not something ASM acts on by itself.
 *
 * <p>Two levels on purpose. A third would have to be understood by every screen, filter and sort that
 * reads this, and "somewhat urgent" is not a distinction anyone dispatches on.
 */
public enum OrderPriority {
    NORMAL,
    HIGH;

    /**
     * The level a piece of text means, whatever produced it.
     *
     * <p>Lives here rather than in each caller because there were two copies with different rules:
     * one strict (ASM's own API, where the value is already NORMAL or HIGH) and one generous (an ERP
     * mapping, where a customer flags urgency with a checkbox, a French word or a status label). Two
     * functions answering the same question drift, and the strict one silently downgraded every
     * mapped "URGENT" to NORMAL.
     *
     * <p>Generous is the right default for both: the caller with clean input loses nothing, and
     * {@code URGENT} collapses onto HIGH rather than becoming a level nothing downstream knows.
     *
     * <p>Anything unrecognised is NORMAL, never an error. A word nobody anticipated must not fail an
     * import; the worst case is a delivery that is merely not prioritised.
     */
    public static OrderPriority of(String value) {
        if (value == null || value.isBlank()) return NORMAL;
        return switch (value.trim().toLowerCase()) {
            case "high", "urgent", "urgente", "prioritaire", "haute", "1", "true", "yes", "oui" -> HIGH;
            default -> NORMAL;
        };
    }
}
