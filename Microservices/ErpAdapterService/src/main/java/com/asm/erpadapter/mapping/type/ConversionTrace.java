package com.asm.erpadapter.mapping.type;

import com.asm.erpadapter.mapping.CanonicalField;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records what each field's conversion actually did, while a preview is being built.
 *
 * <p>The import only needs the value; the preview needs the reason. Without this, a blank cell on the
 * mapping screen means "empty in the ERP", "unreadable" and "wrong path" at once — which is the exact
 * ambiguity {@link ConversionOutcome} exists to remove, thrown away one layer later.
 *
 * <p>A thread-local rather than a request-scoped bean so the same reader works unchanged in the
 * scheduled sync, where there is no request at all. Off by default: outside a
 * {@link #start()}/{@link #stop()} pair, recording costs one null check and keeps nothing, so the
 * import path is untouched.
 */
@Component
public class ConversionTrace {

    private static final ThreadLocal<Map<String, ConversionOutcome>> ACTIVE = new ThreadLocal<>();

    /** Begin collecting on this thread. Always pair with {@link #stop()} in a finally block. */
    public void start() {
        ACTIVE.set(new LinkedHashMap<>());
    }

    /** Stop collecting and hand back what was seen; empty when nothing was recorded. */
    public Map<String, ConversionOutcome> stop() {
        Map<String, ConversionOutcome> collected = ACTIVE.get();
        ACTIVE.remove();
        return collected != null ? collected : Map.of();
    }

    /**
     * Note one field's outcome, if anyone is listening.
     *
     * <p>Line fields are recorded once — the first row that had something to say. A preview shows one
     * verdict per canonical field, and twenty identical "unreadable quantity" entries would bury the
     * one field that is genuinely misconfigured.
     */
    public void record(CanonicalField field, ConversionOutcome outcome) {
        Map<String, ConversionOutcome> collecting = ACTIVE.get();
        if (collecting == null || field == null) return;

        ConversionOutcome seen = collecting.get(field.name());
        // An unreadable row outranks an empty one: it is the actionable finding.
        if (seen == null || (seen.isEmpty() && !outcome.isEmpty())
                || (seen.isValue() && outcome.isUnreadable())) {
            collecting.put(field.name(), outcome);
        }
    }
}
