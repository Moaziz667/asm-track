package com.asm.erpadapter.mapping.type.converter;

import com.asm.erpadapter.mapping.type.CanonicalConverter;
import com.asm.erpadapter.mapping.type.CanonicalType;
import com.asm.erpadapter.mapping.type.Compatibility;
import com.asm.erpadapter.mapping.type.ConversionOutcome;
import com.asm.erpadapter.mapping.type.SourceType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.asm.erpadapter.mapping.type.Compatibility.LOSSY;
import static com.asm.erpadapter.mapping.type.Compatibility.SAFE;

/**
 * Flags — is the shipment ready, must the driver collect payment.
 *
 * <p>The only converter that must not treat {@code false} as absence: for every other target Odoo's
 * {@code false} means "unset", but here it is the answer.
 *
 * <p>{@link SourceType#ENUM} is accepted as {@link Compatibility#LOSSY} because customers routinely
 * express readiness with a status word rather than a checkbox — {@code state = assigned} is how Odoo
 * itself says it. Recognising a fixed vocabulary of affirmative codes is a guess, an informed one, and
 * the integrator is told it is a guess. Any other word reads as false rather than unreadable: a status
 * that is not "ready" is a legitimate "not ready", not a broken mapping.
 */
@Component
public class BooleanConverter implements CanonicalConverter {

    /** Words an ERP uses to mean yes. {@code assigned} and {@code done} are Odoo picking states. */
    private static final Set<String> TRUTHY =
            Set.of("true", "1", "yes", "y", "oui", "assigned", "ready", "done", "completed");

    @Override
    public CanonicalType target() {
        return CanonicalType.BOOLEAN;
    }

    @Override
    public Map<SourceType, Compatibility> accepts() {
        return Map.of(
                SourceType.BOOLEAN, SAFE,
                // 0/1 columns are still common in customer-added fields.
                SourceType.INTEGER, SAFE,
                SourceType.ENUM, LOSSY,
                SourceType.TEXT, LOSSY);
    }

    @Override
    public ConversionOutcome convert(Object raw) {
        if (raw == null) return ConversionOutcome.empty();
        if (raw instanceof Boolean b) return ConversionOutcome.of(b);
        if (raw instanceof Number n) return ConversionOutcome.of(n.doubleValue() != 0d);

        String text = ErpValues.text(raw).toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) return ConversionOutcome.empty();
        return ConversionOutcome.of(TRUTHY.contains(text));
    }
}
