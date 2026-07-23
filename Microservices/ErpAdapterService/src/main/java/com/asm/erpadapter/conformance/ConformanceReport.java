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
     * @param capability human key, e.g. {@code "stock.move.line.qty_done"} or {@code "write:stock.picking"}
     * @param kind       what was probed
     * @param severity   REQUIRED (fails verdict) or RECOMMENDED (downgrades to DEGRADED)
     * @param status     the outcome
     * @param detail     human-readable note (why it failed, or the version-derived resolution)
     */
    public record CapabilityCheck(
            String capability,
            Kind kind,
            Severity severity,
            Status status,
            String detail
    ) {}

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
