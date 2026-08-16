package com.asm.erpadapter.mapping.type.converter;

import com.asm.erpadapter.mapping.type.CanonicalConverter;
import com.asm.erpadapter.mapping.type.CanonicalType;
import com.asm.erpadapter.mapping.type.Compatibility;
import com.asm.erpadapter.mapping.type.ConversionOutcome;
import com.asm.erpadapter.mapping.type.SourceType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static com.asm.erpadapter.mapping.type.Compatibility.LOSSY;
import static com.asm.erpadapter.mapping.type.Compatibility.SAFE;

/**
 * Dates, as {@link LocalDateTime}.
 *
 * <p>This converter is why the design puts the declaration and the conversion in the same object.
 * {@link SourceType#DATE} was previously listed as mappable while the reader only ever attempted
 * date-<em>time</em> formats, so pointing the promised delivery date at a day-only field — the
 * obvious choice, and the one every integrator makes — parsed nothing and stored a silent null. SLA
 * and route planning then ran on a delivery with no date at all.
 *
 * <p>Here, accepting {@code DATE} and reading {@code "2026-08-16"} are the same commitment: the entry
 * below cannot exist without the branch that handles it.
 *
 * <p>A day-only source is {@link Compatibility#LOSSY}, not {@link Compatibility#SAFE}: midnight is
 * invented. It is a reasonable convention and a wrong hour, and an integrator planning delivery
 * windows deserves to know the hour came from us and not from his ERP.
 */
@Component
public class DateTimeConverter implements CanonicalConverter {

    /** Odoo serialises datetimes without the ISO {@code T}. */
    private static final DateTimeFormatter ODOO_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public CanonicalType target() {
        return CanonicalType.DATE_TIME;
    }

    @Override
    public Map<SourceType, Compatibility> accepts() {
        return Map.of(
                SourceType.DATE_TIME, SAFE,
                // Supported, and read: the time is set to start of day.
                SourceType.DATE, LOSSY,
                // Customers keep dates in text fields more often than anyone would like.
                SourceType.TEXT, LOSSY);
    }

    @Override
    public ConversionOutcome convert(Object raw) {
        if (ErpValues.isAbsent(raw)) return ConversionOutcome.empty();
        if (raw instanceof LocalDateTime dt) return ConversionOutcome.of(dt);
        if (raw instanceof LocalDate d) return ConversionOutcome.of(d.atStartOfDay());

        String text = ErpValues.text(raw);
        if (text.isEmpty()) return ConversionOutcome.empty();

        for (Parser p : PARSERS) {
            LocalDateTime parsed = p.parse(text);
            if (parsed != null) return ConversionOutcome.of(parsed);
        }
        return ConversionOutcome.unreadable("« " + text + " » n'est pas une date reconnue.");
    }

    @FunctionalInterface
    private interface Parser {
        /** The parsed value, or null when this shape does not apply. */
        LocalDateTime parse(String text);
    }

    /** Ordered most specific first, so a full timestamp is never truncated by a looser rule. */
    private static final List<Parser> PARSERS = List.of(
            text -> tryParse(() -> LocalDateTime.parse(text, ODOO_DATE_TIME)),
            text -> tryParse(() -> LocalDateTime.parse(text.replace("Z", ""))),
            // The branch the old reader lacked entirely.
            text -> tryParse(() -> LocalDate.parse(text).atStartOfDay()),
            // A timestamp that carries an offset: keep the instant, drop the zone, as ASM stores local.
            text -> tryParse(() -> java.time.OffsetDateTime.parse(text).toLocalDateTime()));

    private static LocalDateTime tryParse(java.util.function.Supplier<LocalDateTime> attempt) {
        try {
            return attempt.get();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
