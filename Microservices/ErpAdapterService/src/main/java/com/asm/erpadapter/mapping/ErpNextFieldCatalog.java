package com.asm.erpadapter.mapping;

import com.asm.erpadapter.adapter.erpnext.ErpNextRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists the fields of a tenant's ERPNext that a mapping may point at.
 *
 * <p>Reads the {@code DocType} document itself, whose {@code fields} child table is the doctype's own
 * definition — standard fields and anything this instance added, in one round trip. Simpler than the
 * Odoo side, which needs a {@code fields_get} call per model.
 *
 * @see ErpFieldCatalog for why this is fetched fresh rather than cached
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpNextFieldCatalog implements ErpFieldCatalog {

    /**
     * Fieldtypes worth offering.
     *
     * <p>Excluded on purpose: the layout types ({@code Section Break}, {@code Column Break},
     * {@code Tab Break}, {@code HTML}, {@code Heading}, {@code Fold}) carry no value at all and would
     * be pure noise in a list already hundreds long; {@code Table} and {@code Table MultiSelect}
     * because one ASM value cannot come from a list of rows; and the attachment types
     * ({@code Attach}, {@code Image}, {@code Signature}) because a file path in a customer-name field
     * is never what anyone meant.
     */
    private static final Set<String> MAPPABLE_TYPES = Set.of(
            "Data", "Small Text", "Text", "Long Text", "Text Editor", "Markdown Editor",
            "Link", "Dynamic Link", "Select", "Autocomplete",
            "Int", "Float", "Currency", "Percent", "Rating",
            "Date", "Datetime", "Time", "Duration",
            "Check", "Read Only", "Phone", "Barcode", "Color", "Password");

    private final ErpNextRestClient rest;

    @Override
    public String provider() {
        return "erpnext";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Field> fieldsOf(String doctype) {
        if (doctype == null || doctype.isBlank()) return List.of();

        Map<String, Object> meta = rest.getDoc("DocType", doctype.trim());
        if (meta == null || !(meta.get("fields") instanceof List<?> rows)) {
            log.warn("Field catalog: could not read the definition of doctype={}", doctype);
            return List.of();
        }

        List<Field> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> f) addField(out, f, false);
        }
        // Appended, not merely flagged: Frappe keeps added fields in their own Custom Field doctype
        // and they are absent from DocType.fields entirely. Reading only the doctype would leave the
        // picker offering the standard fields and none of the ones a human is here to choose.
        for (Map<String, Object> f : customFields(doctype.trim())) {
            addField(out, f, true);
        }
        out.sort(Comparator.comparing(Field::label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Add one field definition, whichever doctype it was declared in. */
    private static void addField(List<Field> out, Map<?, ?> f, boolean custom) {
        String name = str(f.get("fieldname"));
        String type = str(f.get("fieldtype"));
        if (name == null || type == null || !MAPPABLE_TYPES.contains(type)) return;

        // For a Link, `options` names the target doctype — so a relation is walkable without the
        // guessed lookup table Odoo needs, where the record itself never says what it points at.
        String relation = "Link".equals(type) ? str(f.get("options")) : null;
        out.add(new Field(name, firstNonBlank(str(f.get("label")), name), type, relation, custom));
    }

    /**
     * The fields added to {@code doctype} on this instance, with their definitions.
     *
     * <p>Read from the {@code Custom Field} doctype rather than inferred from the {@code custom_}
     * prefix. Frappe only applies that prefix to fields created through Customize Form, so a field
     * added by an installed app — or by any instance older than v15 — carries no marker at all. On
     * the demo instance every single Custom Field record fails the prefix test, which would have made
     * the heuristic silently useless.
     *
     * <p>This does mean fields contributed by apps appear alongside the customer's own. That is the
     * honest answer to the question the star actually asks: this field is not part of standard
     * ERPNext, and mapping onto it is legitimate.
     */
    private List<Map<String, Object>> customFields(String doctype) {
        return rest.getList("Custom Field",
                List.of("fieldname", "label", "fieldtype", "options"),
                List.of(List.of("dt", "=", doctype)), 500, null);
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }
}
