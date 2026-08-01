package com.asm.erpadapter.conformance;

import com.asm.erpadapter.adapter.odoo.CapabilityRegistry;
import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import com.asm.erpadapter.adapter.odoo.OdooVersionResolver;
import com.asm.erpadapter.conformance.ConformanceReport.CapabilityCheck;
import com.asm.erpadapter.conformance.ConformanceReport.Kind;
import com.asm.erpadapter.conformance.ConformanceReport.Severity;
import com.asm.erpadapter.conformance.ConformanceReport.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only conformance probe for Odoo. Verifies — without any write — that the exact models, fields and
 * access rights the {@code OdooSyncAdapter} depends on exist on the tenant's live instance, and detects
 * the version. Every finding a specific known cross-version break maps to (e.g. {@code qty_done} →
 * {@code quantity} in Odoo 17+, {@code create_returns} → {@code action_create_returns} in Odoo 19) is
 * surfaced explicitly so a tenant is certified GO/DEGRADED/NO-GO before its sync is ever enabled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooConformanceProbe implements ErpConformanceProbe {

    private final OdooJsonRpcClient rpc;
    private final OdooVersionResolver versionResolver;
    private final CapabilityRegistry registry;
    private final com.asm.erpadapter.adapter.odoo.MethodResolver methodResolver;

    @Override
    public String provider() {
        return "odoo";
    }

    @Override
    public ConformanceReport probe() {
        String version = versionResolver.version();
        int major = versionResolver.major();
        List<CapabilityCheck> checks = new ArrayList<>();

        // ── Core read models + fields the adapter reads/writes ────────────────────────────────────
        checkFields(checks, "sale.order", Severity.REQUIRED, List.of("state", "name"));
        checkField(checks, "sale.order", "commitment_date", Severity.RECOMMENDED,
                "Needed by syncReschedule (push new delivery date).");
        checkField(checks, "sale.order", "invoice_ids", Severity.RECOMMENDED,
                "Needed by the create-invoice flow.");

        checkFields(checks, "stock.picking", Severity.REQUIRED,
                List.of("state", "name", "sale_id", "backorder_id"));
        checkFields(checks, "stock.move", Severity.REQUIRED,
                List.of("picking_id", "product_id", "product_uom_qty", "origin_returned_move_id"));

        // ── The signature cross-version break: qty_done (≤16) vs quantity (17+) ────────────────────
        checkDoneQtyField(checks, major);

        checkModel(checks, "stock.return.picking", Severity.REQUIRED,
                "Reverse stock move (RMA) wizard.");
        checkFields(checks, "stock.return.picking.line", Severity.REQUIRED,
                List.of("wizard_id", "product_id", "quantity"));
        checkField(checks, "product.product", "default_code", Severity.REQUIRED,
                "SKU matching for partial deliveries and returns.");

        checkFields(checks, "stock.scrap", Severity.RECOMMENDED, List.of("product_id", "scrap_qty"));
        checkModel(checks, "ir.attachment", Severity.RECOMMENDED, "POD photo attachments.");

        // ── Access rights the write path needs (integration user must actually be allowed) ─────────
        checkAccess(checks, "stock.picking", "write", Severity.REQUIRED);
        checkAccess(checks, "stock.move.line", "write", Severity.REQUIRED);
        checkAccess(checks, "stock.return.picking", "create", Severity.REQUIRED);
        checkAccess(checks, "stock.scrap", "create", Severity.RECOMMENDED);
        checkAccess(checks, "ir.attachment", "create", Severity.RECOMMENDED);

        // ── Methods the write path calls ──────────────────────────────────────────────────────────
        // These were reported UNKNOWN on the assumption that method existence "is not verifiable
        // read-only". That assumption is exactly what let two capabilities reach live use
        // unresolvable — SET_FULL_QUANTITY (dropped in Odoo 19) and FORCE_AVAILABILITY (absent from
        // every current version) — each dead-lettering a delivery the driver had already made.
        // MethodResolver probes on an EMPTY recordset: the method iterates nothing, so the probe has
        // no side effects even when the method is destructive. Methods are therefore verifiable, and
        // a missing one now fails certification instead of surfacing months later in production.
        checkMethod(checks, "DELIVERY_VALIDATE", Severity.REQUIRED,
                "Validates the transfer; without it nothing can be delivered.");
        checkMethod(checks, "RESERVE_STOCK", Severity.REQUIRED,
                "Reserves stock before validation.");
        checkMethod(checks, "CREATE_RETURN", Severity.REQUIRED,
                "Reverse stock move for RMAs.");
        checkMethod(checks, "CANCEL_DELIVERY", Severity.REQUIRED,
                "Cancels the picking when an order is cancelled.");
        checkMethod(checks, "BACKORDER_CONFIRM", Severity.REQUIRED,
                "Confirms the backorder wizard after a partial delivery.");
        // RECOMMENDED, not REQUIRED: the adapter has an equivalent path when these are absent, so a
        // version lacking them is degraded, not unusable.
        // Both were dropped by Odoo, not by this customer: 19 removed the set-quantities button, and
        // force-availability has been gone since before the oldest version ASM supports. Flagging
        // either as a gap sends an integrator hunting for a repair no Odoo of that version can offer.
        checkMethod(checks, "SET_FULL_QUANTITY", Severity.RECOMMENDED,
                "Full-delivery shortcut; falls back to marking the reserved lines picked.", 19, major);
        checkMethod(checks, "FORCE_AVAILABILITY", Severity.RECOMMENDED,
                "Nudge for unreservable stock; skipped when absent (quantities are written explicitly).",
                16, major);

        // Probed like every other method rather than asserted from the version number. Reporting it
        // as UNKNOWN listed a capability this instance does have among the ones it lacks, which reads
        // as a defect in the integration rather than a gap in our own certification.
        checkMethod(checks, "UNLOCK_SALE_ORDER", Severity.RECOMMENDED,
                major >= 19 ? "Odoo 19 auto-locks confirmed orders; unlock-before-cancel required."
                            : "No-op on Odoo ≤18 (orders are not auto-locked).");

        // ── The delivery note now comes from Odoo, so its report must exist here ──────────────────
        checkDeliverySlipReport(checks);

        ConformanceReport.Verdict verdict = ConformanceReport.deriveVerdict(checks);
        log.info("provider=odoo operation=conformanceProbe version={} verdict={} checks={}",
                version, verdict, checks.size());
        return new ConformanceReport("odoo", version, verdict, checks, Instant.now());
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    //  Checks
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /** Verify a model exists (fields_get succeeds) — reported under the model name itself. */
    /**
     * Verify that at least one candidate method of a capability actually exists on this instance.
     *
     * <p>Probes on an empty recordset, so it is side-effect free regardless of what the method does.
     * INCONCLUSIVE (transport failure) maps to UNKNOWN, which never fails the verdict — an unreachable
     * instance is not evidence of a missing method.
     */
    private void checkMethod(List<CapabilityCheck> checks, String capability, Severity sev, String note) {
        checkMethod(checks, capability, sev, note, 0, 0);
    }

    /**
     * Same, for a method the vendor removed in a known release.
     *
     * <p>An absence the vendor chose is not a defect in the customer's instance. Reporting it as
     * MISSING drags the whole verdict to DEGRADED, so a current, healthy Odoo 19 is presented as an
     * installation with gaps — and the integrator goes looking for something to repair that no
     * version of Odoo 19 will ever have. Below {@code absentFrom} the absence is still real news.
     *
     * @param absentFrom the first major version where the absence is expected, {@code 0} if never
     * @param major      the version actually running
     */
    private void checkMethod(List<CapabilityCheck> checks, String capability, Severity sev, String note,
                             int absentFrom, int major) {
        List<String> candidates;
        String model;
        try {
            candidates = registry.getCandidates(capability);
            model = registry.getModel(capability);
        } catch (Exception e) {
            checks.add(new CapabilityCheck(capability, Kind.METHOD, sev, Status.UNKNOWN,
                    "Not declared in the capability registry. " + note));
            return;
        }

        boolean inconclusive = false;
        for (String candidate : candidates) {
            com.asm.erpadapter.adapter.odoo.MethodResolver.Probe p = methodResolver.probe(model, candidate);
            if (p == com.asm.erpadapter.adapter.odoo.MethodResolver.Probe.EXISTS) {
                checks.add(new CapabilityCheck(model + "." + candidate, Kind.METHOD, sev, Status.OK, note));
                return;
            }
            if (p == com.asm.erpadapter.adapter.odoo.MethodResolver.Probe.INCONCLUSIVE) inconclusive = true;
        }
        String name = model + "." + String.join("|", candidates);
        if (inconclusive) {
            checks.add(new CapabilityCheck(name, Kind.METHOD, sev, Status.UNKNOWN,
                    "Could not be probed (transport failure). " + note));
            return;
        }
        if (absentFrom > 0 && major >= absentFrom) {
            checks.add(new CapabilityCheck(name, Kind.METHOD, sev, Status.OK,
                    "Absent from Odoo " + absentFrom + " onward — expected on this version, not a gap "
                            + "in this instance; the adapter's fallback covers it. " + note));
            return;
        }
        checks.add(new CapabilityCheck(name, Kind.METHOD, sev, Status.MISSING,
                "No candidate exists on this instance: " + candidates + ". " + note));
    }

    /**
     * Verify the instance can produce a delivery-note PDF.
     *
     * <p>ASM stopped drawing its own bon de livraison and now serves Odoo's, so a tenant whose instance
     * defines no {@code qweb-pdf} report for {@code stock.picking} has no delivery note at all. That must
     * surface at certification, not the morning a driver is waiting at the depot for a document.
     *
     * <p>RECOMMENDED rather than REQUIRED: the sync itself works fine without it — only the printed
     * document is lost — and a NO_GO would block deliveries over a paperwork gap.
     */
    @SuppressWarnings("unchecked")
    private void checkDeliverySlipReport(List<CapabilityCheck> checks) {
        String name = "ir.actions.report[stock.picking]";
        String note = "Delivery note PDF is fetched from Odoo; without a qweb-pdf report on stock.picking "
                + "no bon de livraison can be printed.";
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("ir.actions.report", "search_count",
                    List.of(List.of(
                            List.of("model", "=", "stock.picking"),
                            List.of("report_type", "=", "qweb-pdf")))));
            if (resp == null || resp.containsKey("error")) {
                checks.add(new CapabilityCheck(name, Kind.MODEL, Severity.RECOMMENDED, Status.UNKNOWN,
                        "Could not be probed (transport or access failure). " + note));
                return;
            }
            Integer count = com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.asInt(resp.get("result"));
            boolean present = count != null && count > 0;
            checks.add(new CapabilityCheck(name, Kind.MODEL, Severity.RECOMMENDED,
                    present ? Status.OK : Status.MISSING,
                    present ? note : "No qweb-pdf report defined for stock.picking on this instance. " + note));
        } catch (Exception e) {
            checks.add(new CapabilityCheck(name, Kind.MODEL, Severity.RECOMMENDED, Status.UNKNOWN,
                    "Probe failed: " + e.getMessage() + ". " + note));
        }
    }

    private void checkModel(List<CapabilityCheck> checks, String model, Severity sev, String note) {
        Map<String, Object> fields = fieldsGet(model);
        Status status = fields != null ? Status.OK : Status.MISSING;
        checks.add(new CapabilityCheck(model, Kind.MODEL, sev, status,
                fields != null ? note : "Model not found on this instance. " + note));
    }

    /** Verify several fields of a model in one fields_get round-trip. */
    private void checkFields(List<CapabilityCheck> checks, String model, Severity sev, List<String> fieldNames) {
        Map<String, Object> fields = fieldsGet(model);
        if (fields == null) {
            for (String f : fieldNames) {
                checks.add(new CapabilityCheck(model + "." + f, Kind.FIELD, sev, Status.MISSING,
                        "Model " + model + " not found."));
            }
            return;
        }
        for (String f : fieldNames) {
            boolean present = fields.containsKey(f);
            checks.add(new CapabilityCheck(model + "." + f, Kind.FIELD, sev,
                    present ? Status.OK : Status.MISSING,
                    present ? null : "Field absent on " + model + "."));
        }
    }

    /** Single-field check with an explanatory note. */
    private void checkField(List<CapabilityCheck> checks, String model, String field, Severity sev, String note) {
        Map<String, Object> fields = fieldsGet(model);
        boolean present = fields != null && fields.containsKey(field);
        checks.add(new CapabilityCheck(model + "." + field, Kind.FIELD, sev,
                present ? Status.OK : Status.MISSING,
                present ? note : "Field absent. " + note));
    }

    /**
     * The done-quantity field on {@code stock.move.line} is the highest-value check: Odoo 17 renamed
     * {@code qty_done} → {@code quantity}. The adapter now resolves this field via the CapabilityMap
     * ({@link CapabilityRegistry#getCandidates(String)}), so the probe verifies that the exact field the adapter
     * WILL write for this version is actually present — GO on both ≤16 and 17+, NO_GO only on a genuine
     * mismatch (the field the CapabilityMap picked is absent on the instance).
     */
    private void checkDoneQtyField(List<CapabilityCheck> checks, int major) {
        String expected = registry.getCandidates("DONE_QUANTITY").get(0);
        Map<String, Object> fields = fieldsGet("stock.move.line");
        boolean present = fields != null && fields.containsKey(expected);
        checks.add(new CapabilityCheck("stock.move.line." + expected, Kind.FIELD, Severity.REQUIRED,
                present ? Status.OK : Status.MISSING,
                present ? "Done-quantity field the adapter writes for Odoo " + (major > 0 ? major : "?") + "."
                        : (fields == null ? "Model stock.move.line not found."
                          : "CapabilityMap picked '" + expected + "' for Odoo " + (major > 0 ? major : "?")
                            + " but it is absent — version detection/boundary mismatch.")));
    }

    /** Verify the integration user actually has the given access right on a model. */
    private void checkAccess(List<CapabilityCheck> checks, String model, String op, Severity sev) {
        Boolean allowed = checkAccessRights(model, op);
        Status status = allowed == null ? Status.MISSING : (allowed ? Status.OK : Status.DENIED);
        String detail = switch (status) {
            case DENIED -> "Integration user lacks '" + op + "' on " + model + ".";
            case MISSING -> "Could not verify (model missing or unreachable).";
            default -> null;
        };
        checks.add(new CapabilityCheck(op + ":" + model, Kind.ACCESS, sev, status, detail));
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    //  Low-level read-only Odoo calls
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /** {@code fields_get} on a model → field→meta map, or null if the model doesn't exist / call failed. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fieldsGet(String model) {
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(model, "fields_get",
                    List.of(), Map.of("attributes", List.of("type"))));
            if (resp == null || resp.containsKey("error")) return null;
            Object result = resp.get("result");
            return result instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (Exception e) {
            log.debug("fields_get failed for {}: {}", model, e.getMessage());
            return null;
        }
    }

    /** {@code check_access_rights(op, raise_exception=false)} → Boolean, or null on error/missing model. */
    private Boolean checkAccessRights(String model, String op) {
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(model, "check_access_rights",
                    List.of(op), Map.of("raise_exception", false)));
            if (resp == null || resp.containsKey("error")) return null;
            Object result = resp.get("result");
            return result instanceof Boolean b ? b : null;
        } catch (Exception e) {
            log.debug("check_access_rights failed for {} {}: {}", op, model, e.getMessage());
            return null;
        }
    }

}
