package com.asm.erpadapter.mapping;

import java.util.Map;

/**
 * Reads a {@link CanonicalField} out of one ERP's records, honouring the tenant's overrides.
 *
 * <p>One implementation per ERP family, selected by {@code ErpProviderRouter} — the same shape as
 * {@code ErpConformanceProbe}. The split matters: the <em>vocabulary</em> ({@link CanonicalField}) is
 * shared so a single mapping screen serves every provider, while <em>how to walk a path</em> is not —
 * {@code partner_id.name} is Odoo's shape and returns {@code [id, "label"]}, where ERPNext returns a
 * plain string from {@code customer.customer_name}. Pushing that knowledge down here keeps the
 * provider's quirks out of the shared vocabulary.
 *
 * <p><b>Defaults stay in code, overrides are data.</b> An implementation resolves a field by asking
 * the tenant's mapping first and falling back to the built-in reader — which for composed values
 * (an address assembled from four parts, a name resolved through a fallback chain) is real logic, not
 * a field name. So a tenant that has overridden nothing behaves byte-for-byte as before this existed,
 * which is what makes it safe to introduce across ERPs already in production.
 */
public interface FieldMappingResolver {

    /** Lowercase provider key this resolver serves, e.g. {@code "odoo"}. */
    String provider();

    /**
     * Which documents a mapping of {@code scope} may read from, and which one a bare path hangs off.
     *
     * <p>Declared here because it is the one rule the picker and the resolver must agree on, and the
     * one whose disagreement is silent: offering a line row a header-relative path writes a mapping
     * that resolves against a document with no such field, so the import yields nothing and looks
     * like an empty ERP rather than a bad mapping. Serving it from the resolver that will later walk
     * the path leaves a single source of truth instead of the same table copied into the frontend.
     *
     * @param scope   whether the field is filled once per order or once per article
     * @return the primary document plus every other document in scope, most relevant first
     */
    MappingScope scopeFor(CanonicalField.Scope scope);

    /**
     * Where this field is read from when the tenant has mapped nothing.
     *
     * <p>Descriptive only — the real default is the supplier the adapter passes in, which for a
     * composed value is logic rather than a field name. It exists so the screen can say
     * "défaut · res.partner.phone" instead of a bare "défaut", which is the difference between an
     * integrator knowing what they are about to override and guessing at it.
     *
     * @return the path the built-in reader uses, or {@link #DERIVED} when several fields combine into
     *         one and naming any single candidate would be wrong more often than right
     */
    String defaultSourceFor(CanonicalField field);

    /** Marks a default no single path can express, so the UI renders it as prose instead. */
    String DERIVED = "—";

    /**
     * The documents a mapping may start from, for one scope.
     *
     * @param primary    the document a path with no {@code Model:} prefix is relative to
     * @param addressable every document in scope including {@code primary}, most relevant first
     */
    record MappingScope(String primary, java.util.List<String> addressable) {}

    /**
     * The tenant's mapped value for one canonical field, or the caller's own default when they have
     * not mapped it.
     *
     * <p>The default is passed in as a supplier rather than declared as data, because for a composed
     * value — the recipient's name resolved through a sale → partner → picking fallback chain, an
     * address assembled from four parts — the default is logic, not a field name. Expressing it here
     * would be lossy and would create a second place to keep correct. This way a tenant who has mapped
     * nothing goes through exactly the code that ran before mapping existed.
     *
     * <p>Taking the supplier (rather than returning an Optional) also removes a real ambiguity: an
     * override that resolves to {@code null} must win over the default. If the integrator points a
     * field at {@code x_ville} and it is empty, the honest answer is empty — falling back to the
     * standard city would hide the wrong mapping behind plausible data. An Optional return could not
     * distinguish "not mapped" from "mapped to nothing".
     *
     * @param field    what to read
     * @param records  the ERP documents in play, keyed by model
     *                 (e.g. {@code stock.picking}, {@code sale.order}, {@code res.partner})
     * @param builtIn  the reader to use when the tenant has no mapping for this field
     * @return the mapped value, or the built-in default
     */
    Object resolveOrDefault(CanonicalField field,
                            Map<String, Map<String, Object>> records,
                            java.util.function.Supplier<Object> builtIn);

    /**
     * The extra fields of {@code model} that must be fetched for this tenant's mappings to resolve.
     *
     * <p>Adapters read a fixed field list from the ERP — asking for everything on every document would
     * be wasteful. But a mapping points at a field nobody anticipated, by definition: unless the
     * adapter widens its request, {@code x_client_nom} is simply absent from the record and the
     * mapping silently yields nothing. That is the worst possible outcome, because the integrator
     * cannot tell "my ERP field is empty" from "the system never asked for it".
     *
     * <p>Only the first segment of a path is returned: fetching {@code partner_id} is what makes
     * {@code partner_id.city} walkable, and the rest is read from the related record.
     *
     * @return field names to add to the adapter's own list; empty when the tenant mapped nothing
     */
    java.util.Set<String> extraFieldsFor(String model);

    /**
     * The customer-defined extras for this tenant, keyed by the label the integrator chose.
     *
     * <p>These have no canonical equivalent, so ASM cannot reason about them — they are carried
     * through to the order's {@code custom_fields} bag and displayed as-is. Returning them here rather
     * than through {@link #resolve} keeps the closed vocabulary closed.
     */
    Map<String, Object> resolveCustomFields(Map<String, Map<String, Object>> records);
}
