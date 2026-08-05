package com.asm.erpadapter.conformance;

import java.time.Instant;
import java.util.List;

/**
 * Result of a read-only conformance probe ("drytest") against a tenant's live ERP instance.
 *
 * <p>The probe verifies — <b>without any write / side effect</b> — that the exact models, fields and
 * access rights the ASM adapter depends on actually exist on the target instance, and reports a
 * go/no-go verdict. It is the per-tenant certification gate: an instance that fails a REQUIRED
 * capability must not be allowed to run the ERP sync, because the adapter would break on it at runtime
 * (e.g. an Odoo 17+ instance where {@code stock.move.line.qty_done} was renamed to {@code quantity}).
 *
 * <p>Method existence (e.g. {@code create_returns} vs {@code action_create_returns}) cannot be verified
 * read-only without invoking it, so those checks are reported as {@link Status#UNKNOWN} and derived from
 * the detected version rather than probed — they never fail the verdict on their own.
 */
public record ConformanceReport(
        String provider,
        String detectedVersion,
        Verdict verdict,
        List<CapabilityCheck> checks,
        Instant checkedAt
) {

    /** Overall certification outcome. */
    public enum Verdict {
        /** All REQUIRED capabilities present — the tenant can safely run the sync. */
        GO,
        /** All REQUIRED present, but a RECOMMENDED capability is missing — works with limitations. */
        DEGRADED,
        /** A REQUIRED capability is missing/denied — the sync would break; activation must be blocked. */
        NO_GO
    }

    /** How badly a missing capability hurts. */
    public enum Severity { REQUIRED, RECOMMENDED }

    /** Per-capability outcome. */
    public enum Status {
        /** Present / granted. */
        OK,
        /** The model or field does not exist on this instance. */
        MISSING,
        /** The model exists but the integration user lacks the access right. */
        DENIED,
        /** Not verifiable read-only (e.g. a method name) — derived from the detected version. */
        UNKNOWN
    }

    public enum Kind { MODEL, FIELD, METHOD, ACCESS }

    /**
     * A single capability check.
     *
     * <h2>Why there is both a {@code detail} and a {@code reasonKey}</h2>
     * {@code detail} is English prose assembled here, and the admin UI is served in French, English
     * and Arabic. Prose cannot be translated downstream, so the UI needs something it can look up.
     *
     * <p>Only the <em>reason</em> is keyed, not the whole sentence. What a capability is for ("SKU
     * matching for partial deliveries") the UI can derive from {@link #capability()}, which it already
     * has. What its status <em>means</em> it cannot: an absence the vendor chose is reported
     * {@link Status#OK}, and by name alone that row is indistinguishable from a capability the
     * instance actually has — which is precisely the distinction this report exists to draw.
     *
     * <p>{@code detail} remains the authoritative English text: it is part of the API contract, it is
     * what a non-UI consumer reads, and it is the fallback wherever a key has no translation yet.
     *
     * @param capability   human key, e.g. {@code "stock.move.line.qty_done"} or {@code "write:stock.picking"}
     * @param kind         what was probed
     * @param severity     REQUIRED (fails verdict) or RECOMMENDED (downgrades to DEGRADED)
     * @param status       the outcome
     * @param detail       human-readable note in English (why it failed, or the version-derived resolution)
     * @param reasonKey    stable key naming why the status is what it is; {@code null} when the status
     *                     speaks for itself (a field that is simply present)
     * @param reasonParams values the translated reason interpolates, e.g. {@code {version: 19}}
     */
    public record CapabilityCheck(
            String capability,
            Kind kind,
            Severity severity,
            Status status,
            String detail,
            String reasonKey,
            java.util.Map<String, Object> reasonParams
    ) {
        /** Un-keyed check — nothing to explain beyond the capability name and its status. */
        public CapabilityCheck(String capability, Kind kind, Severity severity, Status status, String detail) {
            this(capability, kind, severity, status, detail, null, null);
        }

        /** Keyed check with no interpolated values. */
        public CapabilityCheck(String capability, Kind kind, Severity severity, Status status,
                               String detail, String reasonKey) {
            this(capability, kind, severity, status, detail, reasonKey, null);
        }
    }

    /**
     * Derive the overall verdict from the individual checks:
     * any REQUIRED that is not OK/UNKNOWN → NO_GO; else any RECOMMENDED not OK/UNKNOWN → DEGRADED; else GO.
     * UNKNOWN never fails a verdict (it is a version-derived expectation, not a proven gap).
     */
    public static Verdict deriveVerdict(List<CapabilityCheck> checks) {
        boolean degraded = false;
        for (CapabilityCheck c : checks) {
            boolean failed = c.status() == Status.MISSING || c.status() == Status.DENIED;
            if (!failed) continue;
            if (c.severity() == Severity.REQUIRED) return Verdict.NO_GO;
            degraded = true;
        }
        return degraded ? Verdict.DEGRADED : Verdict.GO;
    }
}
