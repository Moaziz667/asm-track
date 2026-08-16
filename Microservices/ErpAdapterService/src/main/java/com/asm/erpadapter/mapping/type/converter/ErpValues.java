package com.asm.erpadapter.mapping.type.converter;

import java.util.List;

/**
 * What "empty" means across the ERPs we read.
 *
 * <p>Shared because the answer must not depend on the provider. Odoo returns {@code false} for every
 * unset field whatever its declared type, ERPNext returns {@code null} or {@code ""}; treating one as
 * empty and the other as a value is how the same mapping ended up behaving differently on two ERPs.
 */
public final class ErpValues {

    private ErpValues() {}

    /**
     * Whether the ERP is saying "nothing here".
     *
     * <p>{@code Boolean.FALSE} counts — but only for non-boolean targets, which is why the boolean
     * converter does not call this: for it, {@code false} is a real answer, not an absence.
     */
    public static boolean isAbsent(Object raw) {
        if (raw == null) return true;
        if (Boolean.FALSE.equals(raw)) return true;
        if (raw instanceof String s) return s.isBlank();
        if (raw instanceof List<?> l) return l.isEmpty();
        return false;
    }

    /** The text of a value, with an Odoo relation reduced to its label. */
    public static String text(Object raw) {
        if (raw instanceof List<?> rel && rel.size() > 1 && rel.get(1) != null) {
            return String.valueOf(rel.get(1)).trim();
        }
        return String.valueOf(raw).trim();
    }
}
