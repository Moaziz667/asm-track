package com.asm.erpadapter.mapping.type;

import com.asm.erpadapter.mapping.CanonicalField;
import com.asm.erpadapter.mapping.FieldMappingResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads one canonical field out of an ERP's records: resolve the tenant's mapping, then convert.
 *
 * <p>Shared by every adapter, which is the point. Each adapter used to carry its own
 * {@code mappedString / mappedInt / mappedDecimal / mappedDateTime / mappedBoolean} — the same five
 * methods written twice, with the same canonical field free to be read as a String on one ERP and an
 * Integer on the other, and nothing anywhere to notice. Here the target type comes from
 * {@link CanonicalField#type()}, so both providers are structurally incapable of disagreeing.
 *
 * <p>Typed accessors rather than one {@code Object}-returning call, so a mismatch between the field's
 * declared type and the variable it is assigned to fails at compile time instead of at run time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MappedValueReader {

    private final CanonicalConverterRegistry registry;

    /**
     * The full outcome, for callers that can act on the difference between empty and unreadable —
     * the preview, and any import path that reports conversion failures.
     */
    public ConversionOutcome read(FieldMappingResolver resolver, CanonicalField field,
                                  Map<String, Map<String, Object>> records, Supplier<Object> builtIn) {
        Object raw = resolver.resolveOrDefault(field, records, builtIn);
        ConversionOutcome outcome = registry.convert(field, raw);
        if (outcome.isUnreadable()) {
            // Logged once per field per record: an integrator debugging a mapping needs to find this
            // in the service log, and support needs it when the preview is not in front of them.
            log.warn("Field mapping: {} could not be read from this record — {}", field, outcome.reason());
        }
        return outcome;
    }

    public String text(FieldMappingResolver r, CanonicalField f,
                       Map<String, Map<String, Object>> records, Supplier<String> builtIn) {
        return (String) read(r, f, records, builtIn::get).valueOrNull();
    }

    public Integer integer(FieldMappingResolver r, CanonicalField f,
                           Map<String, Map<String, Object>> records, Supplier<Integer> builtIn) {
        return (Integer) read(r, f, records, builtIn::get).valueOrNull();
    }

    public BigDecimal decimal(FieldMappingResolver r, CanonicalField f,
                              Map<String, Map<String, Object>> records, Supplier<BigDecimal> builtIn) {
        return (BigDecimal) read(r, f, records, builtIn::get).valueOrNull();
    }

    public LocalDateTime dateTime(FieldMappingResolver r, CanonicalField f,
                                  Map<String, Map<String, Object>> records, Supplier<LocalDateTime> builtIn) {
        return (LocalDateTime) read(r, f, records, builtIn::get).valueOrNull();
    }

    /**
     * A flag, defaulting to false when nothing is known.
     *
     * <p>False rather than null because the two flags in the model — is it ready, must the driver
     * collect — are both "no unless stated". Guessing yes on an absent value would send a driver to
     * ask a customer for money on the strength of a blank field.
     */
    public boolean flag(FieldMappingResolver r, CanonicalField f,
                        Map<String, Map<String, Object>> records, Supplier<Boolean> builtIn) {
        Object v = read(r, f, records, builtIn::get).valueOrNull();
        return v instanceof Boolean b && b;
    }
}
