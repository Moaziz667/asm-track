package com.asm.erpadapter.mapping.type;

import com.asm.erpadapter.mapping.CanonicalField;
import com.asm.erpadapter.mapping.ErpNextFieldCatalog;
import com.asm.erpadapter.mapping.ErpFieldCatalog;
import com.asm.erpadapter.mapping.OdooFieldCatalog;
import com.asm.erpadapter.mapping.type.converter.BooleanConverter;
import com.asm.erpadapter.mapping.type.converter.DateTimeConverter;
import com.asm.erpadapter.mapping.type.converter.DecimalConverter;
import com.asm.erpadapter.mapping.type.converter.IntegerConverter;
import com.asm.erpadapter.mapping.type.converter.TextConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One suite for both ERPs.
 *
 * <p>The rule this file enforces is the reason the type system exists: <b>same canonical contract +
 * different ERP metadata = same conversion rules</b>. Writing an Odoo suite and an ERPNext suite would
 * let the two drift exactly as the adapters used to, and the drift would be invisible because each
 * suite would pass.
 *
 * <p>So the providers appear here only as a source of type names — {@code char} against {@code Data} —
 * and every assertion after normalisation is shared.
 */
class CanonicalConversionContractTest {

    private final CanonicalConverterRegistry registry = new CanonicalConverterRegistry(List.of(
            new TextConverter(), new IntegerConverter(), new DecimalConverter(),
            new BooleanConverter(), new DateTimeConverter()));

    private final ErpFieldCatalog odoo = new OdooFieldCatalog(null);
    private final ErpFieldCatalog erpnext = new ErpNextFieldCatalog(null);

    // ── Both ERPs must normalise to the same vocabulary ───────────────────────────────────────────

    /** Odoo name, ERPNext name, the one ASM type they must both become. */
    static Stream<org.junit.jupiter.params.provider.Arguments> equivalentTypes() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("char", "Data", SourceType.TEXT),
                org.junit.jupiter.params.provider.Arguments.of("text", "Long Text", SourceType.TEXT),
                org.junit.jupiter.params.provider.Arguments.of("html", "Text Editor", SourceType.RICH_TEXT),
                org.junit.jupiter.params.provider.Arguments.of("selection", "Select", SourceType.ENUM),
                org.junit.jupiter.params.provider.Arguments.of("many2one", "Link", SourceType.RELATION),
                org.junit.jupiter.params.provider.Arguments.of("integer", "Int", SourceType.INTEGER),
                org.junit.jupiter.params.provider.Arguments.of("float", "Float", SourceType.DECIMAL),
                org.junit.jupiter.params.provider.Arguments.of("monetary", "Currency", SourceType.DECIMAL),
                org.junit.jupiter.params.provider.Arguments.of("boolean", "Check", SourceType.BOOLEAN),
                org.junit.jupiter.params.provider.Arguments.of("date", "Date", SourceType.DATE),
                org.junit.jupiter.params.provider.Arguments.of("datetime", "Datetime", SourceType.DATE_TIME));
    }

    @ParameterizedTest(name = "odoo:{0} + erpnext:{1} → {2}")
    @MethodSource("equivalentTypes")
    void bothProvidersNormaliseToTheSameAsmType(String odooType, String erpNextType, SourceType expected) {
        assertThat(odoo.normalize(odooType)).isEqualTo(expected);
        assertThat(erpnext.normalize(erpNextType)).isEqualTo(expected);
    }

    /** A type nobody named must never be silently read as text. */
    @Test
    void anUnknownTypeIsUnknownOnBothProviders() {
        assertThat(odoo.normalize("one2many")).isEqualTo(SourceType.UNKNOWN);
        assertThat(erpnext.normalize("Table")).isEqualTo(SourceType.UNKNOWN);
        assertThat(registry.compatibility(CanonicalField.ITEM_QUANTITY, SourceType.UNKNOWN))
                .isEqualTo(Compatibility.UNSUPPORTED);
    }

    /** Every type either catalogue offers must be judgeable — none may fall through to UNKNOWN. */
    @Test
    void everyOfferedTypeIsNamed() {
        for (String t : List.of("char", "text", "html", "selection", "integer", "float",
                "monetary", "date", "datetime", "boolean", "many2one")) {
            assertThat(odoo.normalize(t)).as("odoo type %s", t).isNotEqualTo(SourceType.UNKNOWN);
        }
        for (String t : List.of("Data", "Small Text", "Text", "Long Text", "Text Editor",
                "Markdown Editor", "Link", "Dynamic Link", "Select", "Autocomplete", "Int", "Float",
                "Currency", "Percent", "Rating", "Date", "Datetime", "Time", "Duration", "Check",
                "Read Only", "Phone", "Barcode", "Color", "Password")) {
            assertThat(erpnext.normalize(t)).as("erpnext type %s", t).isNotEqualTo(SourceType.UNKNOWN);
        }
    }

    // ── Compatibility, derived from the converters ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} ← {1} is {2}")
    @CsvSource({
            "ITEM_QUANTITY,       INTEGER,   SAFE",
            "ITEM_QUANTITY,       DECIMAL,   LOSSY",
            "ITEM_QUANTITY,       TEXT,      UNSUPPORTED",
            "ITEM_QUANTITY,       RELATION,  UNSUPPORTED",
            "TOTAL_AMOUNT,        DECIMAL,   SAFE",
            "TOTAL_AMOUNT,        INTEGER,   SAFE",
            "TOTAL_AMOUNT,        TEXT,      UNSUPPORTED",
            "CUSTOMER_NAME,       TEXT,      SAFE",
            "CUSTOMER_NAME,       RELATION,  LOSSY",
            "CUSTOMER_NAME,       ENUM,      LOSSY",
            "SCHEDULED_AT,        DATE_TIME, SAFE",
            "SCHEDULED_AT,        DATE,      LOSSY",
            "SCHEDULED_AT,        INTEGER,   UNSUPPORTED",
            "READY,               BOOLEAN,   SAFE",
            "READY,               ENUM,      LOSSY",
            "READY,               DATE,      UNSUPPORTED",
    })
    void compatibilityIsDerivedFromTheRegisteredConverters(String field, String source, String expected) {
        assertThat(registry.compatibility(CanonicalField.valueOf(field), SourceType.valueOf(source)))
                .isEqualTo(Compatibility.valueOf(expected));
    }

    // ── Conversion, including the values that used to disappear ───────────────────────────────────

    @Test
    void wholeNumbersConvertExactly() {
        assertThat(registry.convert(CanonicalField.ITEM_QUANTITY, 4).value()).isEqualTo(4);
        assertThat(registry.convert(CanonicalField.ITEM_QUANTITY, 4.0d).value()).isEqualTo(4);
    }

    /** The quantity bug: a fractional line silently became a different number of parcels. */
    @Test
    void aFractionalQuantityRoundsAndTheMappingIsMarkedLossy() {
        assertThat(registry.convert(CanonicalField.ITEM_QUANTITY, 2.5d).value()).isEqualTo(3);
        assertThat(registry.compatibility(CanonicalField.ITEM_QUANTITY, SourceType.DECIMAL))
                .isEqualTo(Compatibility.LOSSY);
    }

    @Test
    void amountsKeepTheirDecimalsExactly() {
        assertThat(registry.convert(CanonicalField.TOTAL_AMOUNT, 0.1d).value())
                .isEqualTo(new BigDecimal("0.1"));
        assertThat(registry.convert(CanonicalField.TOTAL_AMOUNT, 12).value())
                .isEqualTo(BigDecimal.valueOf(12d));
    }

    /** The date bug: a day-only source parsed as nothing and stored a silent null. */
    @Test
    void aDateWithoutATimeIsRead() {
        ConversionOutcome out = registry.convert(CanonicalField.SCHEDULED_AT, "2026-08-16");
        assertThat(out.isValue()).isTrue();
        assertThat(out.value()).isEqualTo(LocalDateTime.of(2026, 8, 16, 0, 0));
    }

    @Test
    void odooAndIsoTimestampsAreBothRead() {
        assertThat(registry.convert(CanonicalField.SCHEDULED_AT, "2026-08-16 09:30:00").value())
                .isEqualTo(LocalDateTime.of(2026, 8, 16, 9, 30));
        assertThat(registry.convert(CanonicalField.SCHEDULED_AT, "2026-08-16T09:30:00").value())
                .isEqualTo(LocalDateTime.of(2026, 8, 16, 9, 30));
    }

    @Test
    void aRelationYieldsItsLabelNotItsRawPair() {
        assertThat(registry.convert(CanonicalField.CUSTOMER_NAME, List.of(42, "Grant Plastics")).value())
                .isEqualTo("Grant Plastics");
    }

    /** Odoo answers `false` for every unset field; that is absence, not the string "false". */
    @ParameterizedTest
    @CsvSource({"ERP_ORDER_ID", "TOTAL_AMOUNT", "SCHEDULED_AT", "ITEM_QUANTITY"})
    void odooFalseIsEmptyForEveryNonBooleanTarget(String field) {
        assertThat(registry.convert(CanonicalField.valueOf(field), Boolean.FALSE).state())
                .isEqualTo(ConversionOutcome.State.EMPTY);
    }

    /** …but for a flag it is the answer. */
    @Test
    void falseIsARealAnswerForAFlag() {
        ConversionOutcome out = registry.convert(CanonicalField.READY, Boolean.FALSE);
        assertThat(out.isValue()).isTrue();
        assertThat(out.value()).isEqualTo(false);
    }

    @ParameterizedTest
    @CsvSource({"ERP_ORDER_ID", "TOTAL_AMOUNT", "SCHEDULED_AT", "ITEM_QUANTITY"})
    void nullAndBlankAreEmpty(String field) {
        CanonicalField f = CanonicalField.valueOf(field);
        assertThat(registry.convert(f, null).state()).isEqualTo(ConversionOutcome.State.EMPTY);
        assertThat(registry.convert(f, "   ").state()).isEqualTo(ConversionOutcome.State.EMPTY);
    }

    /** The distinction the old code destroyed: a broken value is not an empty one. */
    @Test
    void anInvalidValueIsUnreadableNotEmpty() {
        ConversionOutcome qty = registry.convert(CanonicalField.ITEM_QUANTITY, "abc");
        assertThat(qty.state()).isEqualTo(ConversionOutcome.State.UNREADABLE);
        assertThat(qty.reason()).contains("abc");

        assertThat(registry.convert(CanonicalField.SCHEDULED_AT, "la semaine prochaine").state())
                .isEqualTo(ConversionOutcome.State.UNREADABLE);
        assertThat(registry.convert(CanonicalField.TOTAL_AMOUNT, "+216 20 000 000").state())
                .isEqualTo(ConversionOutcome.State.UNREADABLE);
    }

    /** An empty amount must not become zero: "collect nothing" and "unknown" are opposite orders. */
    @Test
    void anEmptyAmountStaysEmptyRatherThanBecomingZero() {
        assertThat(registry.convert(CanonicalField.COD_AMOUNT, Boolean.FALSE).valueOrNull()).isNull();
        assertThat(registry.convert(CanonicalField.COD_AMOUNT, null).valueOrNull()).isNull();
    }

    // ── The guarantees the registry itself makes ──────────────────────────────────────────────────

    /** Start-up must fail rather than let a whole field import as blank. */
    @Test
    void aCanonicalTypeWithNoConverterStopsTheApplication() {
        assertThatThrownBy(() -> new CanonicalConverterRegistry(List.of(new TextConverter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CanonicalConverter is registered")
                .hasMessageContaining("ITEM_QUANTITY");
    }

    @Test
    void twoConvertersForOneTypeIsRefused() {
        assertThatThrownBy(() -> new CanonicalConverterRegistry(
                List.of(new TextConverter(), new TextConverter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same canonical type");
    }

    /** Every canonical field is reachable — the guarantee that keeps the two ERPs aligned. */
    @Test
    void everyCanonicalFieldHasAConverter() {
        for (CanonicalField f : CanonicalField.values()) {
            assertThat(registry.converterFor(f.type())).as("converter for %s", f).isNotNull();
        }
    }

    /** The matrix is computed, never maintained — it can only describe what is registered. */
    @Test
    void theMatrixReportsOnlyRegisteredCapabilities() {
        var matrix = registry.matrix();
        assertThat(matrix.get(CanonicalType.INTEGER))
                .containsEntry(SourceType.INTEGER, Compatibility.SAFE)
                .containsEntry(SourceType.DECIMAL, Compatibility.LOSSY)
                .doesNotContainKey(SourceType.TEXT);
        assertThat(matrix.get(CanonicalType.DATE_TIME)).containsKey(SourceType.DATE);
    }

    /**
     * Guards against a converter claiming a source type it has no branch for — the failure that made
     * a mappable {@code date} produce nothing for months.
     *
     * <p>The sample is chosen per (target, source) pair, not per source type alone. Accepting a type
     * is not a promise that every value of it converts: a text field may hold {@code "2026-08-16"} or
     * {@code "la semaine prochaine"}, and refusing the second is the correct answer, not a gap. What
     * must never happen is a converter unable to read even a well-formed value of a type it accepts.
     */
    @Test
    void everyAcceptedSourceTypeHasARepresentativeValueThatConverts() {
        for (CanonicalType target : CanonicalType.values()) {
            CanonicalConverter converter = registry.converterFor(target);
            for (SourceType source : SourceType.values()) {
                if (!converter.compatibilityWith(source).isAllowed()) continue;

                Object sample = wellFormedSample(target, source);
                ConversionOutcome out = converter.convert(sample);
                assertThat(out.isUnreadable())
                        .as("%s accepts %s but cannot read a well-formed %s", target, source, sample)
                        .isFalse();
            }
        }
    }

    /** A value that is both plausible for {@code source} and meaningful for {@code target}. */
    private static Object wellFormedSample(CanonicalType target, SourceType source) {
        return switch (source) {
            case INTEGER -> 3;
            case DECIMAL -> 3.5d;
            case BOOLEAN -> true;
            case RELATION -> List.of(1, "Label");
            case DATE -> "2026-08-16";
            case DATE_TIME -> "2026-08-16 09:30:00";
            case ENUM -> target == CanonicalType.BOOLEAN ? "assigned" : "draft";
            case RICH_TEXT -> "<p>Sonner deux fois</p>";
            // Free text holding what this target is actually after.
            case TEXT -> switch (target) {
                case DATE_TIME -> "2026-08-16";
                case INTEGER -> "3";
                case DECIMAL -> "3.5";
                case BOOLEAN -> "oui";
                case TEXT -> "abc";
            };
            case UNKNOWN -> throw new AssertionError("UNKNOWN must never be accepted by any converter");
        };
    }
}
