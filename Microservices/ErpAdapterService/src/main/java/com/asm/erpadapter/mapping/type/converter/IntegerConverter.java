package com.asm.erpadapter.mapping.type.converter;

import com.asm.erpadapter.mapping.type.CanonicalConverter;
import com.asm.erpadapter.mapping.type.CanonicalType;
import com.asm.erpadapter.mapping.type.Compatibility;
import com.asm.erpadapter.mapping.type.ConversionOutcome;
import com.asm.erpadapter.mapping.type.SourceType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

import static com.asm.erpadapter.mapping.type.Compatibility.LOSSY;
import static com.asm.erpadapter.mapping.type.Compatibility.SAFE;

/**
 * Whole numbers.
 *
 * <p>Text is <b>not</b> accepted, and that is the point of the whole exercise: a phone number, a
 * customer reference and an order name are all {@code char} in Odoo, and every one of them parses to
 * nothing useful. Refusing at the picker is the difference between an integrator seeing a greyed-out
 * field and a customer receiving the wrong number of parcels.
 *
 * <p>{@link SourceType#DECIMAL} is accepted as {@link Compatibility#LOSSY}: Odoo's default quantity
 * field ({@code product_uom_qty}) is a float, so refusing it outright would block the normal mapping —
 * but a line measured in kilos or litres genuinely loses its fraction, and the integrator has to be
 * told rather than discover it on an invoice.
 */
@Component
public class IntegerConverter implements CanonicalConverter {

    @Override
    public CanonicalType target() {
        return CanonicalType.INTEGER;
    }

    @Override
    public Map<SourceType, Compatibility> accepts() {
        return Map.of(
                SourceType.INTEGER, SAFE,
                // The usual quantity field is a float even when every value in it is whole.
                SourceType.DECIMAL, LOSSY);
    }

    @Override
    public ConversionOutcome convert(Object raw) {
        if (ErpValues.isAbsent(raw)) return ConversionOutcome.empty();

        if (raw instanceof Integer i) return ConversionOutcome.of(i);
        if (raw instanceof Number n) return ConversionOutcome.of((int) Math.round(n.doubleValue()));

        // A field declared numeric can still hold text on a customer instance.
        try {
            return ConversionOutcome.of(new BigDecimal(ErpValues.text(raw))
                    .setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return ConversionOutcome.unreadable(
                    "« " + ErpValues.text(raw) + " » n'est pas un nombre entier.");
        }
    }
}
