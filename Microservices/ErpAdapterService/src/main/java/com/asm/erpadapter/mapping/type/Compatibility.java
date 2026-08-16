package com.asm.erpadapter.mapping.type;

/**
 * How well a {@link SourceType} can fill a {@link CanonicalType}.
 *
 * <p>Three levels rather than two, because the dangerous case is neither of the obvious ones. A
 * binary yes/no would have to call {@code float → INTEGER} compatible — it converts, after all — and
 * that is precisely how a quantity of 2.5 kg became 3 with nobody warned. The middle level exists to
 * make a real loss a decision the integrator takes knowingly, instead of one the system takes for him.
 */
public enum Compatibility {

    /** Converts with no loss of meaning. */
    SAFE,

    /** Converts, but something is lost or guessed. The UI must say what, and the human must accept it. */
    LOSSY,

    /** No converter accepts this source type. The mapping is refused. */
    UNSUPPORTED;

    public boolean isAllowed() {
        return this != UNSUPPORTED;
    }
}
