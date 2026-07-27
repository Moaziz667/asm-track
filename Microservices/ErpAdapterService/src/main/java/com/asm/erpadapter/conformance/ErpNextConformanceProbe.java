package com.asm.erpadapter.conformance;

import com.asm.erpadapter.adapter.erpnext.ErpNextRestClient;
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
 * Read-only conformance probe for ERPNext / Frappe.
 *
 * <p>ERPNext had no probe at all, so the certification gate was a no-op for it: a tenant reached
 * CONNECTED on valid credentials alone, exactly the blind spot that let two Odoo capabilities go
 * live unresolvable. This closes it for the second provider.
 *
 * <p>Strictly read-only, as the interface requires: every check is a {@code get_list} bounded to one
 * row, or a whitelisted-method existence test issued <b>without</b> its required arguments. The
 * latter is the ERPNext analogue of Odoo's empty-recordset probe — Frappe resolves and permission-
 * checks the method before binding arguments, so a missing method answers 404 while a present one
 * answers 417/400 ("missing argument"). Either way nothing is created.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpNextConformanceProbe implements ErpConformanceProbe {

    private final ErpNextRestClient erp;

    @Override
    public String provider() {
        return "erpnext";
    }

    @Override
    public ConformanceReport probe() {
        List<CapabilityCheck> checks = new ArrayList<>();
        String version = detectVersion();

        // ── Doctypes + the exact fields the adapter reads ─────────────────────────────────────────
        checkFields(checks, "Sales Order", Severity.REQUIRED,
                List.of("name", "status", "docstatus", "customer", "delivery_date", "company"));
        checkFields(checks, "Sales Order Item", Severity.REQUIRED,
                List.of("item_code", "qty", "delivered_qty", "parent"));
        checkFields(checks, "Delivery Note", Severity.REQUIRED,
                List.of("name", "status", "docstatus", "is_return", "return_against"));
        checkFields(checks, "Delivery Note Item", Severity.REQUIRED,
                List.of("item_code", "qty", "against_sales_order", "parent"));
        checkFields(checks, "Item", Severity.REQUIRED, List.of("item_code", "item_name"));
        checkFields(checks, "Customer", Severity.REQUIRED, List.of("name", "customer_name"));
        checkFields(checks, "Address", Severity.RECOMMENDED,
                List.of("address_line1", "city", "pincode"));
        // POD evidence: the photos and the delivery note comment.
        checkFields(checks, "File", Severity.RECOMMENDED,
                List.of("file_name", "attached_to_doctype", "attached_to_name", "is_private"));
        checkFields(checks, "Comment", Severity.RECOMMENDED,
                List.of("comment_type", "reference_doctype", "reference_name"));

        // ── Whitelisted methods the write path calls ──────────────────────────────────────────────
        checkMethod(checks, "erpnext.selling.doctype.sales_order.sales_order.make_delivery_note",
                Severity.REQUIRED, "Builds the delivery note from the sales order — the core sync.");
        checkMethod(checks, "erpnext.stock.doctype.delivery_note.delivery_note.make_sales_return",
                Severity.REQUIRED, "Reverse delivery note for RMAs.");
        checkMethod(checks, "frappe.client.set_value",
                Severity.REQUIRED, "Pushes the rescheduled delivery date.");
        checkMethod(checks, "frappe.client.cancel",
                Severity.REQUIRED, "Cancels the delivery note / order.");
        checkMethod(checks, "erpnext.selling.doctype.sales_order.sales_order.make_sales_invoice",
                Severity.RECOMMENDED, "Invoice creation from the order.");
        checkMethod(checks, "erpnext.stock.doctype.delivery_note.delivery_note.make_sales_invoice",
                Severity.RECOMMENDED, "Invoice creation from the delivery note.");

        // ── Company scoping ───────────────────────────────────────────────────────────────────────
        checkCompany(checks);

        ConformanceReport.Verdict verdict = ConformanceReport.deriveVerdict(checks);
        log.info("provider=erpnext operation=conformanceProbe version={} verdict={} checks={}",
                version, verdict, checks.size());
        return new ConformanceReport("erpnext", version, verdict, checks, Instant.now());
    }

    // ── Checks ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Ask for exactly these fields, bounded to one row. Frappe rejects an unknown field, so a
     * successful call proves both that the doctype is readable and that every field exists.
     */
    private void checkFields(List<CapabilityCheck> checks, String doctype, Severity sev, List<String> fields) {
        try {
            erp.getListStrict(doctype, fields, List.of(), 1, null);
            checks.add(new CapabilityCheck(doctype + " " + fields, Kind.FIELD, sev, Status.OK,
                    "Readable with all required fields."));
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            boolean denied = msg.contains("403") || msg.toLowerCase().contains("permission");
            checks.add(new CapabilityCheck(doctype + " " + fields, Kind.FIELD, sev,
                    transportOnly(msg) ? Status.UNKNOWN : Status.MISSING,
                    denied ? "The integration user cannot read " + doctype + ". " + msg
                           : "Unreadable or a field is absent: " + msg));
        }
    }

    /**
     * Probe a whitelisted method by calling it with no arguments.
     *
     * <p>Frappe resolves the dotted path and checks permissions before binding arguments: a method
     * that does not exist (or is not whitelisted for this user) answers 404/403, while one that does
     * answers with a missing-argument error. Nothing is written either way — the handler never runs.
     */
    private void checkMethod(List<CapabilityCheck> checks, String method, Severity sev, String note) {
        try {
            erp.methodGet(method, Map.of());
            checks.add(new CapabilityCheck(method, Kind.METHOD, sev, Status.OK, note));
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("404") || msg.contains("PermissionError") || msg.contains("403")) {
                checks.add(new CapabilityCheck(method, Kind.METHOD, sev, Status.MISSING,
                        "Not available to the integration user on this instance. " + note));
            } else if (transportOnly(msg)) {
                checks.add(new CapabilityCheck(method, Kind.METHOD, sev, Status.UNKNOWN,
                        "Could not be probed (transport failure). " + note));
            } else {
                // Any other error means the method was found and ran far enough to complain about
                // its arguments — which is exactly what we wanted to learn.
                checks.add(new CapabilityCheck(method, Kind.METHOD, sev, Status.OK, note));
            }
        }
    }

    /** The configured company must exist, or every scoped read silently returns nothing. */
    private void checkCompany(List<CapabilityCheck> checks) {
        String company = erp.companyScope();
        if (company == null || company.isBlank()) {
            checks.add(new CapabilityCheck("company scope", Kind.FIELD, Severity.RECOMMENDED, Status.MISSING,
                    "No company configured — reads are not scoped and may span companies."));
            return;
        }
        try {
            List<Map<String, Object>> rows = erp.getListStrict("Company", List.of("name"),
                    List.of(List.of("name", "=", company)), 1, null);
            boolean found = rows != null && !rows.isEmpty();
            checks.add(new CapabilityCheck("company '" + company + "'", Kind.FIELD, Severity.REQUIRED,
                    found ? Status.OK : Status.MISSING,
                    found ? "Configured company exists."
                          : "No company named '" + company + "' — every scoped read returns nothing."));
        } catch (Exception e) {
            checks.add(new CapabilityCheck("company '" + company + "'", Kind.FIELD, Severity.REQUIRED,
                    Status.UNKNOWN, "Could not verify: " + e.getMessage()));
        }
    }

    /** A network/transport failure says nothing about the ERP's capabilities, so it must not fail one. */
    private static boolean transportOnly(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("timeout") || m.contains("connect") || m.contains("unreachable")
                || m.contains("transport error");
    }

    private String detectVersion() {
        try {
            Object v = erp.methodGet("frappe.utils.change_log.get_versions", Map.of());
            if (v instanceof Map<?, ?> m) {
                Object erpnext = m.get("erpnext");
                if (erpnext instanceof Map<?, ?> e && e.get("version") != null) {
                    return "erpnext " + e.get("version");
                }
            }
        } catch (Exception e) {
            log.debug("ERPNext version detection failed: {}", e.getMessage());
        }
        return "unknown";
    }
}
