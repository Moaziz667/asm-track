package com.asm.erpadapter.mapping;

import com.asm.erpadapter.mapping.type.SourceType;

import java.util.List;

/**
 * Lists the fields of one tenant's ERP that a mapping may point at — what fills the integrator's
 * picker.
 *
 * <p>One implementation per ERP family, selected by provider like {@link FieldMappingResolver} and
 * {@code ErpConformanceProbe}. The shape is shared because the picker is: one screen serves every
 * provider, so it must be handed the same record whether the field came from an Odoo
 * {@code fields_get} or an ERPNext doctype.
 *
 * <p>Implementations read fresh rather than from a cache. A field the customer added five minutes
 * ago has to appear during the onboarding session that prompted it, and this is a low-traffic
 * configuration screen where the round trip is affordable.
 */
public interface ErpFieldCatalog {

    /** Lowercase provider key this catalogue serves, e.g. {@code "odoo"}. */
    String provider();

    /**
     * @param model a document a mapping path may start from
     * @return its mappable fields, sorted for display; empty when the model is unknown or unreachable
     */
    List<Field> fieldsOf(String model);

    /**
     * This ERP's own type name, said in ASM's words.
     *
     * <p>The single point where a provider's vocabulary is allowed to matter. Odoo says {@code char}
     * and ERPNext says {@code Data}; both mean a line of text, and everything downstream —
     * compatibility, conversion, the tests — is written once against the normalised name. Without this
     * seam the type system would have to be duplicated per provider, which is the duplication it
     * exists to prevent.
     *
     * <p>An unrecognised name maps to {@link SourceType#UNKNOWN}, which every converter refuses. That
     * is deliberate: a type nobody has considered must surface as "unsupported" in the picker, not be
     * quietly treated as text and read wrongly.
     *
     * @param rawType the value carried in {@link Field#type()}
     */
    SourceType normalize(String rawType);

    /**
     * One selectable field.
     *
     * @param name     technical name, what goes into the mapping path
     * @param label    the customer's own label, what the integrator recognises
     * @param type     the ERP's own type name, so the UI can warn about an obvious mismatch
     * @param relation target document when this field points at another one — lets the UI offer to
     *                 go one hop further, which is where most customer data lives
     * @param custom   whether it is a field this instance added rather than one the ERP ships. These
     *                 are the reason a human is on this screen at all, so the UI surfaces them ahead
     *                 of the couple of hundred standard ones.
     */
    record Field(String name, String label, String type, String relation, boolean custom) {}
}
