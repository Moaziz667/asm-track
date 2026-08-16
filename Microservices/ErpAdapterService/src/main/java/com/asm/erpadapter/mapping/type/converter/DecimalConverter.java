package com.asm.erpadapter.mapping.type.converter;

import com.asm.erpadapter.mapping.type.CanonicalConverter;
import com.asm.erpadapter.mapping.type.CanonicalType;
import com.asm.erpadapter.mapping.type.Compatibility;
import com.asm.erpadapter.mapping.type.ConversionOutcome;
import com.asm.erpadapter.mapping.type.SourceType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

import static com.asm.erpadapter.mapping.type.Compatibility.SAFE;

/**
 * Amounts and weights, as {@link BigDecimal}.
 *
 * <p>Never {@code double}: these values are money to collect from a customer and weights a vehicle is
 * loaded against, and binary floating point does not represent them exactly.
 *
 * <p>Empty stays empty rather than becoming zero. The two are opposite instructions on a delivery —
 * "collect nothing" and "we do not know what to collect" — and the older shared helper answered zero
 * to both, which printed a confident 0 DT next to a badge saying money was due.
 */
@Component
public class DecimalConverter implements CanonicalConverter {

    @Override
    public CanonicalType target() {
        return CanonicalType.DECIMAL;
    }

    @Override
    public Map<SourceType, Compatibility> accepts() {
        return Map.of(
                SourceType.DECIMAL, SAFE,
                // A whole number is a decimal that happens to end in zero — nothing is lost.
                SourceType.INTEGER, SAFE);
    }

    @Override
    public ConversionOutcome convert(Object raw) {
        if (ErpValues.isAbsent(raw)) return ConversionOutcome.empty();

        if (raw instanceof BigDecimal bd) return ConversionOutcome.of(bd);
        // valueOf(double) goes through the shortest decimal representation, so 0.1 stays 0.1 rather
        // than becoming its binary expansion.
        if (raw instanceof Number n) return ConversionOutcome.of(BigDecimal.valueOf(n.doubleValue()));

        try {
            return ConversionOutcome.of(new BigDecimal(ErpValues.text(raw)));
        } catch (NumberFormatException e) {
            return ConversionOutcome.unreadable(
                    "« " + ErpValues.text(raw) + " » n'est pas un montant.");
        }
    }
}
