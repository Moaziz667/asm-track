package com.asm.erpadapter.mapping.type;

import com.asm.erpadapter.mapping.CanonicalField;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one place that knows how a value gets from an ERP into ASM.
 *
 * <p>It owns no rules of its own. Compatibility is <b>derived</b> from the converters Spring actually
 * registered — {@link #matrix()} is computed, never maintained — so it is impossible for the answer
 * given to the mapping screen to differ from what the import will do. That was the whole failure being
 * fixed: a list of supported types living next to, and drifting from, the code that reads them.
 *
 * <p>The constructor is also the guard. If a canonical field declares a type no converter produces,
 * the application does not start. A missing converter is a whole class of fields that would silently
 * import as blank, and a start-up failure on a developer's screen is cheaper by any measure than a
 * customer discovering it on a delivery.
 */
@Component
@Slf4j
public class CanonicalConverterRegistry {

    private final Map<CanonicalType, CanonicalConverter> byTarget = new EnumMap<>(CanonicalType.class);

    public CanonicalConverterRegistry(List<CanonicalConverter> converters) {
        for (CanonicalConverter c : converters) {
            CanonicalConverter clash = byTarget.put(c.target(), c);
            if (clash != null) {
                // Two converters for one type means the winner depends on bean ordering, and the
                // matrix would describe whichever happened to load last.
                throw new IllegalStateException(
                        "Two converters declare the same canonical type " + c.target() + ": "
                        + clash.getClass().getSimpleName() + " and " + c.getClass().getSimpleName());
            }
        }
        verifyEveryCanonicalFieldIsConvertible();
        log.info("Canonical converters ready: {} type(s), {} canonical field(s) covered",
                byTarget.size(), CanonicalField.values().length);
    }

    /**
     * Fail fast when a canonical field has no way to be filled.
     *
     * <p>Reported for every offending field at once rather than one per restart: someone adding a new
     * canonical type wants the whole list, not a game of whack-a-mole.
     */
    private void verifyEveryCanonicalFieldIsConvertible() {
        Map<CanonicalType, List<String>> missing = new EnumMap<>(CanonicalType.class);
        for (CanonicalField f : CanonicalField.values()) {
            if (!byTarget.containsKey(f.type())) {
                missing.computeIfAbsent(f.type(), t -> new java.util.ArrayList<>()).add(f.name());
            }
        }
        if (missing.isEmpty()) return;

        StringBuilder sb = new StringBuilder(
                "No CanonicalConverter is registered for the following canonical types. "
                + "Every canonical field must have a conversion path or its imports would silently "
                + "produce blanks:");
        missing.forEach((type, fields) -> sb.append("\n  - target type ")
                .append(type).append(" — required by ").append(String.join(", ", fields)));
        throw new IllegalStateException(sb.toString());
    }

    /** The converter producing {@code type}. Never null: the constructor proved it exists. */
    public CanonicalConverter converterFor(CanonicalType type) {
        CanonicalConverter c = byTarget.get(type);
        if (c == null) throw new IllegalStateException("No converter for canonical type " + type);
        return c;
    }

    /** How well {@code source} can fill {@code field}. */
    public Compatibility compatibility(CanonicalField field, SourceType source) {
        if (field == null || source == null) return Compatibility.UNSUPPORTED;
        return converterFor(field.type()).compatibilityWith(source);
    }

    /**
     * Read one raw ERP value into the shape {@code field} declares.
     *
     * <p>The same call serves the preview and the import, which is what makes the preview meaningful:
     * a screen that ran its own conversion would be showing something the import might not reproduce.
     */
    public ConversionOutcome convert(CanonicalField field, Object raw) {
        if (field == null) return ConversionOutcome.empty();
        return converterFor(field.type()).convert(raw);
    }

    /**
     * The full compatibility matrix, derived from what is registered — for the mapping screen and for
     * documentation. Ordered for stable rendering and stable test assertions.
     */
    public Map<CanonicalType, Map<SourceType, Compatibility>> matrix() {
        Map<CanonicalType, Map<SourceType, Compatibility>> out = new EnumMap<>(CanonicalType.class);
        for (Map.Entry<CanonicalType, CanonicalConverter> e : new TreeMap<>(byTarget).entrySet()) {
            Map<SourceType, Compatibility> row = new LinkedHashMap<>();
            for (SourceType s : SourceType.values()) {
                Compatibility c = e.getValue().compatibilityWith(s);
                if (c.isAllowed()) row.put(s, c);
            }
            out.put(e.getKey(), row);
        }
        return out;
    }
}
