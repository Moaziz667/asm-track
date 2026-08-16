package com.asm.erpadapter.mapping.type.converter;

import com.asm.erpadapter.mapping.type.CanonicalConverter;
import com.asm.erpadapter.mapping.type.CanonicalType;
import com.asm.erpadapter.mapping.type.Compatibility;
import com.asm.erpadapter.mapping.type.ConversionOutcome;
import com.asm.erpadapter.mapping.type.SourceType;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.asm.erpadapter.mapping.type.Compatibility.LOSSY;
import static com.asm.erpadapter.mapping.type.Compatibility.SAFE;

/**
 * Anything readable into {@link CanonicalType#TEXT}.
 *
 * <p>The widest converter, and the one where "it converts" hides the most. Three of its sources are
 * marked {@link Compatibility#LOSSY} not because the conversion can fail but because what comes out
 * is not what the integrator was looking at when he chose the field:
 *
 * <ul>
 *   <li>{@link SourceType#ENUM} — an Odoo {@code selection} is stored as its code. Mapping the
 *       delivery state gives {@code "assigned"}, not the "Prêt" shown in the ERP's own screen. The
 *       label lives in the field's definition, not in the record, so no read can recover it.</li>
 *   <li>{@link SourceType#RELATION} — the label side of the relation is taken, so the identity of the
 *       record is dropped. Fine for display, wrong for matching.</li>
 *   <li>{@link SourceType#RICH_TEXT} — the markup is carried through verbatim. A driver reading
 *       delivery instructions would see the tags.</li>
 * </ul>
 *
 * <p>Numbers and dates are accepted too, since writing a number down loses nothing.
 */
@Component
public class TextConverter implements CanonicalConverter {

    @Override
    public CanonicalType target() {
        return CanonicalType.TEXT;
    }

    @Override
    public Map<SourceType, Compatibility> accepts() {
        return Map.of(
                SourceType.TEXT, SAFE,
                SourceType.INTEGER, SAFE,
                SourceType.DECIMAL, SAFE,
                SourceType.BOOLEAN, SAFE,
                SourceType.DATE, SAFE,
                SourceType.DATE_TIME, SAFE,
                // Converts cleanly, but not into what the integrator saw on screen — see the class note.
                SourceType.ENUM, LOSSY,
                SourceType.RELATION, LOSSY,
                SourceType.RICH_TEXT, LOSSY);
    }

    @Override
    public ConversionOutcome convert(Object raw) {
        if (ErpValues.isAbsent(raw)) return ConversionOutcome.empty();
        String text = ErpValues.text(raw);
        return text.isEmpty() ? ConversionOutcome.empty() : ConversionOutcome.of(text);
    }
}
