package com.asm.erpadapter.mapping;

import com.asm.erpadapter.mapping.type.SourceType;
import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists the fields of a tenant's Odoo instance that a mapping may point at.
 *
 * <p>This is what fills the integrator's dropdown. It reads {@code fields_get}, which returns the
 * customer's own {@code x_*} fields alongside the standard ones — the whole point, since those are
 * exactly what cannot be guessed and must be chosen by a human.
 *
 * <p>Deliberately not reusing {@code OdooMetadataCache}: that caches field <em>names</em> only, and a
 * dropdown showing {@code x_a3f} without its label ("Référence interne") is unusable. Reading fresh
 * also means a field the customer added five minutes ago appears immediately, which matters during an
 * onboarding session; this is a low-traffic configuration screen, so the round-trip is affordable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooFieldCatalog implements ErpFieldCatalog {

    @Override
    public String provider() {
        return "odoo";
    }

    /**
     * Types worth offering. Collection types ({@code one2many}, {@code many2many}) are excluded because
     * a single ASM value cannot meaningfully come from a list, and {@code binary} because a photo has
     * no place in a text field — offering them would only invite mappings that silently produce
     * nonsense.
     */
    private static final Set<String> MAPPABLE_TYPES = Set.of(
            "char", "text", "html", "selection", "integer", "float", "monetary",
            "date", "datetime", "boolean", "many2one");

    private final OdooJsonRpcClient rpc;

    /**
     * @param model an Odoo model a mapping path may start from
     * @return its mappable fields, technical name first, sorted by label
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<Field> fieldsOf(String model) {
        if (model == null || model.isBlank()) return List.of();
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(model, "fields_get",
                    List.of(List.of(), List.of("string", "type", "relation", "store"))));
            Object result = resp != null ? resp.get("result") : null;
            if (!(result instanceof Map<?, ?> fields)) {
                log.warn("Field catalog: fields_get returned nothing usable for model={}", model);
                return List.of();
            }

            List<Field> out = new ArrayList<>();
            for (Map.Entry<?, ?> e : fields.entrySet()) {
                if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof Map<?, ?> meta)) continue;
                String type = str(meta.get("type"));
                if (type == null || !MAPPABLE_TYPES.contains(type)) continue;

                out.add(new Field(
                        name,
                        firstNonBlank(str(meta.get("string")), name),
                        type,
                        str(meta.get("relation")),
                        name.startsWith("x_")));
            }
            out.sort(Comparator.comparing(Field::label, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (Exception e) {
            log.warn("Field catalog: could not read fields of {}: {}", model, e.getMessage());
            return List.of();
        }
    }

    /**
     * Odoo's {@code ttype} in ASM's words.
     *
     * <p>Every name in {@link #MAPPABLE_TYPES} is answered here, and only those: a type the picker
     * never offers cannot reach a mapping, and one it offers must always normalise to something a
     * converter can judge. {@code char}, {@code text} and {@code html} are three Odoo types and two
     * ASM ones — the first two are indistinguishable to us, the third is not, because carrying markup
     * into a driver's instructions is a real difference.
     */
    @Override
    public SourceType normalize(String rawType) {
        if (rawType == null) return SourceType.UNKNOWN;
        return switch (rawType.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "char", "text" -> SourceType.TEXT;
            case "html" -> SourceType.RICH_TEXT;
            case "selection" -> SourceType.ENUM;
            case "many2one" -> SourceType.RELATION;
            case "integer" -> SourceType.INTEGER;
            case "float", "monetary" -> SourceType.DECIMAL;
            case "boolean" -> SourceType.BOOLEAN;
            case "date" -> SourceType.DATE;
            case "datetime" -> SourceType.DATE_TIME;
            default -> SourceType.UNKNOWN;
        };
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
