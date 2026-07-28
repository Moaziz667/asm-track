package com.asm.erpadapter.mapping;

import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import com.asm.erpadapter.entity.ErpFieldMapping;
import com.asm.erpadapter.repository.ErpFieldMappingRepository;
import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads mapped values out of Odoo records for the current tenant.
 *
 * <p>A mapping is a dotted path — {@code x_client_nom}, {@code partner_id.name},
 * {@code partner_id.country_id.code} — because most of what a customer wants hangs off a related
 * record rather than the document itself. Walking those relations needs data that was not necessarily
 * pre-fetched, so intermediate records are read on demand and cached for the duration of one import;
 * a path is typically one hop, and the common ones ({@code partner_id}, {@code sale.order}) are
 * already in hand, so in practice this costs nothing.
 *
 * @see FieldMappingResolver for why defaults deliberately stay in the caller's code
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooFieldMappingResolver implements FieldMappingResolver {

    /** The document a bare path is relative to when the mapping names no model. */
    private static final String PRIMARY_MODEL = "stock.picking";

    /** Guards against a mapping like {@code a.b.c.d.e...} turning one import into a fetch storm. */
    private static final int MAX_PATH_DEPTH = 4;

    private final ErpFieldMappingRepository repository;
    private final OdooJsonRpcClient rpc;

    @Override
    public String provider() {
        return "odoo";
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
        ErpFieldMapping m = mapping.get();
        return readPath(m.getSourcePath(), SourceKind.from(m.getReadAs()), records, new HashMap<>());
    }

    @Override
    public java.util.Set<String> extraFieldsFor(String model) {
        UUID tenant = TenantContext.get();
        if (tenant == null || model == null) return java.util.Set.of();

        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (ErpFieldMapping m : repository.findByTenantIdAndProvider(tenant, provider())) {
            String root = rootFieldFor(m.getSourcePath(), model);
            if (root != null) out.add(root);
        }
        return out;
    }

    /**
     * The first path segment, when {@code path} is rooted on {@code model}.
     *
     * <p>A path either names its model explicitly ({@code res.partner:city}) or is relative to the
     * primary document. Returns {@code null} when the path belongs to a different model, so each
     * fetch only widens by what it actually needs.
     */
    private static String rootFieldFor(String path, String model) {
        if (path == null || path.isBlank()) return null;
        String expr = path.trim();
        String target = PRIMARY_MODEL;
        int colon = expr.indexOf(':');
        if (colon > 0) {
            target = expr.substring(0, colon).trim();
            expr = expr.substring(colon + 1).trim();
        }
        if (!target.equals(model)) return null;
        String first = expr.split("\\.")[0].trim();
        return first.isEmpty() ? null : first;
    }

    @Override
    public Map<String, Object> resolveCustomFields(Map<String, Map<String, Object>> records) {
        UUID tenant = TenantContext.get();
        if (tenant == null) return Map.of();

        List<ErpFieldMapping> extras =
                repository.findByTenantIdAndProviderAndCanonicalFieldIsNull(tenant, provider());
        if (extras.isEmpty()) return Map.of();

        // One relation cache for the whole batch: several extras usually hang off the same partner.
        Map<String, Map<String, Object>> fetched = new HashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (ErpFieldMapping m : extras) {
            if (m.getCustomKey() == null || m.getCustomKey().isBlank()) continue;
            Object value = readPath(m.getSourcePath(), SourceKind.from(m.getReadAs()), records, fetched);
            if (value != null) out.put(m.getCustomKey(), value);
        }
        return out;
    }

    // ── Path walking ──────────────────────────────────────────────────────────────────────────────

    /**
     * Walk {@code path} across {@code records}, fetching related records as needed.
     *
     * <p>Accepted shapes:
     * <ul>
     *   <li>{@code city} — a field of the primary document</li>
     *   <li>{@code sale.order:amount_total} — a field of another already-fetched document</li>
     *   <li>{@code partner_id.name} — through a relation, fetched if absent</li>
     * </ul>
     *
     * <p>Returns {@code null} rather than throwing on a broken mapping: a typo in one field must not
     * abort the import of an otherwise valid order, and the integrator sees the blank in the preview
     * screen. The reason is logged at debug so support can explain it.
     */
    private Object readPath(String path, SourceKind kind,
                            Map<String, Map<String, Object>> records,
                            Map<String, Map<String, Object>> fetched) {
        if (path == null || path.isBlank()) return null;

        String model = PRIMARY_MODEL;
        String expr = path.trim();
        int colon = expr.indexOf(':');
        if (colon > 0) {
            model = expr.substring(0, colon).trim();
            expr = expr.substring(colon + 1).trim();
        }

        Map<String, Object> current = records.get(model);
        if (current == null) {
            log.debug("Field mapping: no '{}' record in scope for path '{}'", model, path);
            return null;
        }

        String[] segments = expr.split("\\.");
        if (segments.length > MAX_PATH_DEPTH) {
            log.warn("Field mapping: path '{}' exceeds the maximum depth of {} — ignored", path, MAX_PATH_DEPTH);
            return null;
        }

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i].trim();
            if (segment.isEmpty() || current == null) return null;

            Object value = current.get(segment);
            boolean last = (i == segments.length - 1);
            if (last) return interpret(value, kind);

            // Not the last segment: this must be a relation to step through.
            Integer relId = relationId(value);
            if (relId == null) {
                log.debug("Field mapping: '{}' is not a relation, cannot continue path '{}'", segment, path);
                return null;
            }
            String relModel = relationModel(current, segment);
            if (relModel == null) {
                log.debug("Field mapping: cannot determine the model behind '{}' for path '{}'", segment, path);
                return null;
            }
            current = fetchRecord(relModel, relId, fetched);
        }
        return null;
    }

    /** Apply the read mode once the target value is in hand. */
    private Object interpret(Object value, SourceKind kind) {
        if (kind == SourceKind.RAW) return value;
        if (!(value instanceof List<?> rel) || rel.size() < 2) {
            // Odoo returns false for an empty value; ASM wants nothing rather than the string "false".
            return Boolean.FALSE.equals(value) ? null : value;
        }
        return switch (kind) {
            case ID -> rel.get(0);
            case LABEL, AUTO -> rel.get(1);
            case RAW -> value;
        };
    }

    private static Integer relationId(Object value) {
        if (value instanceof List<?> rel && !rel.isEmpty() && rel.get(0) instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof Number n) return n.intValue();
        return null;
    }

    /**
     * The model a relation field points at.
     *
     * <p>Read from the record's own metadata when the caller supplied it; otherwise inferred from the
     * conventional {@code _id} suffix ({@code partner_id → res.partner} is not derivable, so this only
     * covers the handful of relations worth walking without a fields_get round-trip).
     */
    private String relationModel(Map<String, Object> record, String field) {
        Object declared = record.get("__relations__");
        if (declared instanceof Map<?, ?> rels) {
            Object m = rels.get(field);
            if (m instanceof String s && !s.isBlank()) return s;
        }
        return switch (field) {
            case "partner_id", "partner_shipping_id", "partner_invoice_id" -> "res.partner";
            case "sale_id", "order_id" -> "sale.order";
            case "picking_id" -> "stock.picking";
            case "product_id" -> "product.product";
            case "picking_type_id" -> "stock.picking.type";
            case "warehouse_id" -> "stock.warehouse";
            case "country_id" -> "res.country";
            case "state_id" -> "res.country.state";
            case "currency_id" -> "res.currency";
            case "payment_term_id" -> "account.payment.term";
            case "company_id" -> "res.company";
            case "user_id" -> "res.users";
            default -> null;
        };
    }

    /** Read one related record, once per import. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchRecord(String model, Integer id, Map<String, Map<String, Object>> fetched) {
        String key = model + "#" + id;
        Map<String, Object> hit = fetched.get(key);
        if (hit != null) return hit;
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(model, "read", List.of(List.of(id))));
            Object result = resp != null ? resp.get("result") : null;
            if (result instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> row) {
                Map<String, Object> record = (Map<String, Object>) row;
                fetched.put(key, record);
                return record;
            }
        } catch (Exception e) {
            log.debug("Field mapping: could not read {}#{}: {}", model, id, e.getMessage());
        }
        fetched.put(key, Map.of());
        return Map.of();
    }

    /** Exposed for the preview screen: the models a mapping path may start from. */
    public static List<String> addressableModels() {
        return new ArrayList<>(List.of("stock.picking", "sale.order", "res.partner", "stock.warehouse"));
    }
}
