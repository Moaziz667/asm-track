package com.asm.erpadapter.mapping.type;

/**
 * An ERP field type, said once in ASM's own words.
 *
 * <p>Odoo calls it {@code char}, ERPNext calls it {@code Data}, and both mean "a line of text". The
 * normalisation happens in each {@link com.asm.erpadapter.mapping.ErpFieldCatalog} — the only place
 * that legitimately knows a provider's vocabulary — so that everything downstream (compatibility,
 * conversion, tests) is written once and shared. This is what stops the type system from being an
 * Odoo one wearing a neutral name.
 *
 * <p>Distinctions are kept only where they change the answer. {@code char} and {@code text} both
 * become {@link #TEXT} because nothing treats them differently; {@link #ENUM} and {@link #RELATION}
 * are separate from TEXT because reading them needs a decision the integrator has to make (the key or
 * the label), and {@link #DATE} is separate from {@link #DATE_TIME} because a source with no time is
 * exactly the case that used to produce a silent null.
 */
public enum SourceType {

    /** A line or block of plain text — Odoo {@code char}/{@code text}, ERPNext {@code Data}/{@code Text}. */
    TEXT,

    /** Text carrying markup — Odoo {@code html}, ERPNext {@code Text Editor}. */
    RICH_TEXT,

    /** A closed list of coded values — Odoo {@code selection}, ERPNext {@code Select}. */
    ENUM,

    /** A pointer to another record — Odoo {@code many2one}, ERPNext {@code Link}. */
    RELATION,

    /** Whole number — Odoo {@code integer}, ERPNext {@code Int}. */
    INTEGER,

    /** Fractional number, money included — Odoo {@code float}/{@code monetary}, ERPNext {@code Float}/{@code Currency}. */
    DECIMAL,

    /** Odoo {@code boolean}, ERPNext {@code Check}. */
    BOOLEAN,

    /** A calendar day with no time. */
    DATE,

    /** A day and a time. */
    DATE_TIME,

    /**
     * A type the catalogue offers but this normaliser does not name yet.
     *
     * <p>Deliberately not silently treated as text: an unknown type accepted everywhere is how a
     * mapping ends up reading something nobody checked. It is refused by every converter, so the
     * failure is a visible "unsupported" instead of a value that is wrong in a plausible way.
     */
    UNKNOWN
}
