package com.asm.erpadapter.mapping;

import com.asm.erpadapter.adapter.erpnext.ErpNextRestClient;
import com.asm.erpadapter.entity.ErpFieldMapping;
import com.asm.erpadapter.repository.ErpFieldMappingRepository;
import com.asm.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads mapped values out of ERPNext documents for the current tenant.
 *
 * <p>Same contract as {@link OdooFieldMappingResolver} and noticeably simpler, for two reasons that
 * are ERPNext's doing rather than ours:
 *
 * <ul>
 *   <li><b>No value interpretation.</b> A Link field comes back as a plain string
 *       ({@code "customer": "Grant Plastics Ltd."}), where Odoo returns {@code [42, "Grant Plastics
 *       Ltd."]} and forces every mapping to say which half it meant. The {@code readAs} column stays
 *       in the table for Odoo; here there is nothing to choose.</li>
 *   <li><b>No guessed relations.</b> The doctype definition names the target of every Link in its
 *       {@code options}, so walking {@code customer.customer_name} is a lookup rather than the
 *       hardcoded {@code partner_id → res.partner} table the Odoo resolver has to carry.</li>
 * </ul>
 *
 * @see FieldMappingResolver for why defaults deliberately stay in the caller's code
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpNextFieldMappingResolver implements FieldMappingResolver {

    /**
     * The document a bare header path is relative to when the mapping names none.
     *
     * <p>The Sales Order, not the Delivery Note — and the difference is not cosmetic. ERPNext has no
     * delivery note for an order still waiting to be delivered: the adapter creates one at delivery
     * time. Everything ASM reads here comes from the order, so offering {@code Delivery Note} in the
     * picker would hand the integrator a document that does not exist yet, and every mapping made
     * against it would resolve to nothing.
     */
    private static final String PRIMARY_DOCTYPE = "Sales Order";

    /**
     * The document a bare <em>line</em> path is relative to.
     *
     * <p>A line mapping written as {@code custom_lot} means "on this order row", not "on the order".
     * Resolving both against the order would make every line mapping return nothing, since the order
     * has no such field — a blank that reads as an empty ERP rather than a mistake.
     */
    private static final String LINE_PRIMARY_DOCTYPE = "Sales Order Item";

    /** Header documents, in the order an integrator is most likely to want them. */
    private static final List<String> HEADER_DOCTYPES =
            List.of(PRIMARY_DOCTYPE, "Customer", "Address");

    /**
     * Line documents first, then the header ones.
     *
     * <p>A line may legitimately reach the header — an article row naming its order — but never the
     * reverse: filling one per-order value from a document with many rows has no single answer.
     */
    private static final List<String> LINE_DOCTYPES = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(LINE_PRIMARY_DOCTYPE, "Item"),
            HEADER_DOCTYPES.stream()).toList();

    /** Guards against a mapping like {@code a.b.c.d.e...} turning one import into a fetch storm. */
    private static final int MAX_PATH_DEPTH = 4;

    private final ErpFieldMappingRepository repository;
    private final ErpNextRestClient rest;

    @Override
    public String provider() {
        return "erpnext";
    }

    @Override
    public MappingScope scopeFor(CanonicalField.Scope scope) {
        return scope == CanonicalField.Scope.LINE
                ? new MappingScope(LINE_PRIMARY_DOCTYPE, LINE_DOCTYPES)
                : new MappingScope(PRIMARY_DOCTYPE, HEADER_DOCTYPES);
    }

    @Override
    public String defaultSourceFor(CanonicalField field) {
        return ErpNextDefaultSources.of(field);
    }

    /** Which document a bare path hangs off, given the canonical field it fills. */
    private static String primaryFor(CanonicalField field) {
        return field != null && field.isLine() ? LINE_PRIMARY_DOCTYPE : PRIMARY_DOCTYPE;
    }

    /** Same, from the stored name — an extra (null canonical field) is always header-scoped. */
    private static String primaryFor(String canonicalFieldName) {
        if (canonicalFieldName == null || canonicalFieldName.isBlank()) return PRIMARY_DOCTYPE;
        try {
            return primaryFor(CanonicalField.valueOf(canonicalFieldName));
        } catch (IllegalArgumentException e) {
            return PRIMARY_DOCTYPE;   // a field removed from the enum; treat its rows as header
        }
    }

    @Override
    public Object resolveOrDefault(CanonicalField field,
                                   Map<String, Map<String, Object>> records,
                                   java.util.function.Supplier<Object> builtIn) {
        UUID tenant = TenantContext.get();
        if (tenant == null || field == null) return builtIn.get();

        Optional<ErpFieldMapping> mapping =
                repository.findByTenantIdAndProviderAndCanonicalField(tenant, provider(), field.name());
        if (mapping.isEmpty()) return builtIn.get();

        // Mapped: its result stands even when empty — see FieldMappingResolver#resolveOrDefault.
        return readPath(mapping.get().getSourcePath(), primaryFor(field), records, new HashMap<>());
    }

    @Override
    public java.util.Set<String> extraFieldsFor(String doctype) {
        UUID tenant = TenantContext.get();
        if (tenant == null || doctype == null) return java.util.Set.of();

        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (ErpFieldMapping m : repository.findByTenantIdAndProvider(tenant, provider())) {
            String root = rootFieldFor(m.getSourcePath(), doctype, primaryFor(m.getCanonicalField()));
            if (root != null) out.add(root);
        }
        return out;
    }

    @Override
    public Map<String, Object> resolveCustomFields(Map<String, Map<String, Object>> records) {
        UUID tenant = TenantContext.get();
        if (tenant == null) return Map.of();

        List<ErpFieldMapping> extras =
                repository.findByTenantIdAndProviderAndCanonicalFieldIsNull(tenant, provider());
        if (extras.isEmpty()) return Map.of();

        // One relation cache for the whole batch: several extras usually hang off the same customer.
        Map<String, Map<String, Object>> fetched = new HashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (ErpFieldMapping m : extras) {
            if (m.getCustomKey() == null || m.getCustomKey().isBlank()) continue;
            Object value = readPath(m.getSourcePath(), PRIMARY_DOCTYPE, records, fetched);
            if (value != null) out.put(m.getCustomKey(), value);
        }
        return out;
    }

    // ── Path walking ──────────────────────────────────────────────────────────────────────────────

    /**
     * The first path segment, when {@code path} is rooted on {@code doctype}.
     *
     * <p>A path either names its document explicitly ({@code Customer:territory}) or is relative to
     * the primary one — the delivery row for a line mapping, the delivery note otherwise. Returns
     * {@code null} when the path belongs to another document, so each fetch only widens by what it
     * actually needs.
     */
    private static String rootFieldFor(String path, String doctype, String primaryDoctype) {
        if (path == null || path.isBlank()) return null;
        String expr = path.trim();
        String target = primaryDoctype;
        int colon = expr.indexOf(':');
        if (colon > 0) {
            target = expr.substring(0, colon).trim();
            expr = expr.substring(colon + 1).trim();
        }
        if (!target.equals(doctype)) return null;
        String first = expr.split("\\.")[0].trim();
        return first.isEmpty() ? null : first;
    }

    /**
     * Walk {@code path} across {@code records}, fetching linked documents as needed.
     *
     * <p>Accepted shapes:
     * <ul>
     *   <li>{@code customer_name} — a field of the primary document</li>
     *   <li>{@code Sales Order:po_no} — a field of another already-fetched document</li>
     *   <li>{@code customer.territory} — through a Link, fetched if absent</li>
     * </ul>
     *
     * <p>Returns {@code null} rather than throwing on a broken mapping: a typo in one field must not
     * abort the import of an otherwise valid order, and the integrator sees the blank in the preview
     * screen. The reason is logged at debug so support can explain it.
     */
    private Object readPath(String path, String primaryDoctype,
                            Map<String, Map<String, Object>> records,
                            Map<String, Map<String, Object>> fetched) {
        if (path == null || path.isBlank()) return null;

        String doctype = primaryDoctype;
        String expr = path.trim();
        int colon = expr.indexOf(':');
        if (colon > 0) {
            doctype = expr.substring(0, colon).trim();
            expr = expr.substring(colon + 1).trim();
        }

        Map<String, Object> current = records.get(doctype);
        if (current == null) {
            log.debug("Field mapping: no '{}' document in scope for path '{}'", doctype, path);
            return null;
        }

        String[] segments = expr.split("\\.");
        if (segments.length > MAX_PATH_DEPTH) {
            log.warn("Field mapping: path '{}' exceeds the maximum depth of {} — ignored", path, MAX_PATH_DEPTH);
            return null;
        }

        String currentDoctype = doctype;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i].trim();
            if (segment.isEmpty() || current == null) return null;

            Object value = current.get(segment);
            if (i == segments.length - 1) return normalise(value);

            // Not the last segment: this must be a Link to step through. Its value is the target
            // document's name, and the doctype definition says which doctype that is.
            String linkName = asName(value);
            if (linkName == null) {
                log.debug("Field mapping: '{}' is empty, cannot continue path '{}'", segment, path);
                return null;
            }
            String linkedDoctype = linkTarget(currentDoctype, segment);
            if (linkedDoctype == null) {
                log.debug("Field mapping: '{}' is not a Link on {}, cannot continue path '{}'",
                        segment, currentDoctype, path);
                return null;
            }
            current = fetchDoc(linkedDoctype, linkName, fetched);
            currentDoctype = linkedDoctype;
        }
        return null;
    }

    /**
     * ERPNext returns empty values as {@code null} or {@code ""}; ASM wants nothing in both cases, so
     * a blank never reaches a delivery as an empty-looking but present value.
     */
    private static Object normalise(Object value) {
        if (value instanceof String s) return s.isBlank() ? null : s;
        return value;
    }

    private static String asName(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * The doctype a Link field points at, read from the owning doctype's own definition.
     *
     * <p>Cached for the life of the import: a path walked for every line of an order would otherwise
     * re-read the same definition once per row.
     */
    private final Map<String, Map<String, String>> linkTargets = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private String linkTarget(String doctype, String field) {
        Map<String, String> targets = linkTargets.computeIfAbsent(doctype, dt -> {
            Map<String, String> found = new HashMap<>();
            Map<String, Object> meta = rest.getDoc("DocType", dt);
            if (meta != null && meta.get("fields") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> f)) continue;
                    if (!"Link".equals(f.get("fieldtype"))) continue;
                    if (f.get("fieldname") instanceof String n && f.get("options") instanceof String o
                            && !o.isBlank()) {
                        found.put(n, o);
                    }
                }
            }
            return found;
        });
        return targets.get(field);
    }

    /** Read one linked document, once per import. */
    private Map<String, Object> fetchDoc(String doctype, String name,
                                         Map<String, Map<String, Object>> fetched) {
        String key = doctype + "#" + name;
        Map<String, Object> hit = fetched.get(key);
        if (hit != null) return hit;
        Map<String, Object> doc = rest.getDoc(doctype, name);
        Map<String, Object> result = doc != null ? doc : Map.of();
        fetched.put(key, result);
        return result;
    }
}
