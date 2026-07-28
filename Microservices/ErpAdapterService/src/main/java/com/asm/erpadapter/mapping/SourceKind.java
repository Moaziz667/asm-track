package com.asm.erpadapter.mapping;

/**
 * How to read the raw ERP value once the path has been walked.
 *
 * <p>Needed because Odoo does not return relations as plain values: {@code partner_id} comes back as
 * {@code [42, "Grant Plastics Ltd."]}. Copying that straight into ASM shows the user
 * {@code [42, Grant Plastics Ltd.]}, so the mapping has to say which half is wanted — and sometimes
 * the id is what is wanted, for matching rather than display.
 */
public enum SourceKind {

    /**
     * Decide from the shape of the value: a relation yields its label, anything else yields itself.
     * The default, and right for almost every mapping an integrator will make.
     */
    AUTO,

    /** The human-readable side of a relation ({@code [id, label] → label}). */
    LABEL,

    /** The technical side of a relation ({@code [id, label] → id}), for matching against ERP ids. */
    ID,

    /** No interpretation — hand the value through untouched, for debugging a mapping. */
    RAW;

    public static SourceKind from(String value) {
        if (value == null || value.isBlank()) return AUTO;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
