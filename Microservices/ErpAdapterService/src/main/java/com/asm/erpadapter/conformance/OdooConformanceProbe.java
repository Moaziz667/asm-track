package com.asm.erpadapter.conformance;

import com.asm.erpadapter.adapter.odoo.OdooCapabilities;
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
    private final OdooCapabilities caps;

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

        // ── Version-derived method resolutions (cannot be probed read-only → UNKNOWN, never fails) ─
        checks.add(new CapabilityCheck(
                "stock.return.picking." + caps.createReturnsMethod(),
                Kind.METHOD, Severity.REQUIRED, Status.UNKNOWN,
                "Version-resolved via CapabilityMap; call-with-fallback tries "
                        + caps.createReturnsMethodCandidates() + " (method existence not verifiable read-only)."));
        checks.add(new CapabilityCheck(
                "sale.order.action_unlock", Kind.METHOD, Severity.RECOMMENDED, Status.UNKNOWN,
                major >= 19 ? "Odoo 19 auto-locks confirmed orders; unlock-before-cancel required."
                            : "No-op on Odoo ≤18 (orders are not auto-locked)."));

        ConformanceReport.Verdict verdict = ConformanceReport.deriveVerdict(checks);
        log.info("provider=odoo operation=conformanceProbe version={} verdict={} checks={}",
                version, verdict, checks.size());
        return new ConformanceReport("odoo", version, verdict, checks, Instant.now());
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    //  Checks
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /** Verify a model exists (fields_get succeeds) — reported under the model name itself. */
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
     * ({@link OdooCapabilities#doneQtyField()}), so the probe verifies that the exact field the adapter
     * WILL write for this version is actually present — GO on both ≤16 and 17+, NO_GO only on a genuine
     * mismatch (the field the CapabilityMap picked is absent on the instance).
     */
    private void checkDoneQtyField(List<CapabilityCheck> checks, int major) {
        String expected = caps.doneQtyField();
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
