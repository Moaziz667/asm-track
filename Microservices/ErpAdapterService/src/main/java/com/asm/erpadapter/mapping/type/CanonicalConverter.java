package com.asm.erpadapter.mapping.type;

import java.util.Map;

/**
 * Turns a raw ERP value into one {@link CanonicalType}, and says which source types it can do that
 * for.
 *
 * <p><b>The declaration and the conversion are the same object on purpose.</b> The alternative — a
 * compatibility table kept next to the conversion code — is two artefacts that drift: one can claim
 * {@code date} is supported while the other never learned to read a day without a time, which is
 * exactly the bug this replaces. Here, accepting a source type and knowing how to read it are the
 * same commitment, so the compatibility matrix is not maintained at all. It is
 * {@link CanonicalConverterRegistry#matrix() derived} from what is actually registered.
 *
 * <p>Implementations are Spring beans; the registry collects them. Adding a converter is all it takes
 * to widen support, and there is no second place to remember.
 */
public interface CanonicalConverter {

    /** The canonical type this converter produces. One converter per type. */
    CanonicalType target();

    /**
     * The source types this converter can read, and at what cost.
     *
     * <p>A type absent from this map is {@link Compatibility#UNSUPPORTED}: silence means no.
     */
    Map<SourceType, Compatibility> accepts();

    /**
     * Convert one raw value read from the ERP.
     *
     * <p>Called only for a source type this converter accepts, so an implementation may assume the
     * shape is plausible — but never that it is well-formed. A field typed {@code char} in Odoo can
     * still hold anything, and an integrator can point {@link CanonicalType#DATE_TIME} at free text;
     * the answer then is {@link ConversionOutcome#unreadable}, never an exception and never a guess.
     *
     * @param raw the value as the ERP returned it, after {@code SourceKind} interpretation
     */
    ConversionOutcome convert(Object raw);

    /** Convenience for the registry and for tests. */
    default Compatibility compatibilityWith(SourceType source) {
        return accepts().getOrDefault(source, Compatibility.UNSUPPORTED);
    }
}
