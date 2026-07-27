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
        checkChildFields(checks, "Sales Order", Severity.REQUIRED,
                List.of("item_code", "qty", "delivered_qty"));
        checkFields(checks, "Delivery Note", Severity.REQUIRED,
                List.of("name", "status", "docstatus", "is_return", "return_against"));
        checkChildFields(checks, "Delivery Note", Severity.REQUIRED,
                List.of("item_code", "qty", "against_sales_order"));
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
        // frappe.client.set_value and frappe.client.cancel are core framework APIs that always exist;
        // what actually varies per tenant is whether the integration user may write and cancel. They
        // are also POST-only, so probing them the way the make_* methods are probed answers 403 and
        // would report a working instance as incompatible. Check the permission instead — read-only,
        // and it is the thing that can genuinely be wrong.
        checkPermission(checks, "Sales Order", "write", Severity.REQUIRED,
                "Needed to push the rescheduled delivery date (frappe.client.set_value).");
        checkPermission(checks, "Delivery Note", "cancel", Severity.REQUIRED,
                "Needed to cancel a delivery note (frappe.client.cancel).");
        checkPermission(checks, "Delivery Note", "create", Severity.REQUIRED,
                "Needed to record a delivery.");
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

    /**
     * Verify the fields of a child table by reading them off a real parent document.
     *
     * <p>A child doctype cannot be listed on its own in Frappe — {@code /api/resource/Sales Order Item}
     * answers 403 without a {@code parent} argument — so querying it like a top-level doctype reported
     * a perfectly good instance as broken.
     */
    @SuppressWarnings("unchecked")
    private void checkChildFields(List<CapabilityCheck> checks, String parentDoctype, Severity sev,
                                  List<String> fields) {
        String label = parentDoctype + ".items " + fields;
        try {
            List<Map<String, Object>> parents = erp.getListStrict(parentDoctype, List.of("name"), List.of(), 1, null);
            if (parents == null || parents.isEmpty()) {
                checks.add(new CapabilityCheck(label, Kind.FIELD, sev, Status.UNKNOWN,
                        "No " + parentDoctype + " exists yet to inspect its lines."));
                return;
            }
            Map<String, Object> doc = erp.getDoc(parentDoctype, String.valueOf(parents.get(0).get("name")));
            Object raw = doc != null ? doc.get("items") : null;
            if (!(raw instanceof List<?> rows) || rows.isEmpty() || !(rows.get(0) instanceof Map<?, ?> row)) {
                checks.add(new CapabilityCheck(label, Kind.FIELD, sev, Status.UNKNOWN,
                        "The sampled " + parentDoctype + " has no lines to inspect."));
                return;
            }
            List<String> missing = new ArrayList<>();
            for (String f : fields) if (!row.containsKey(f)) missing.add(f);
            checks.add(new CapabilityCheck(label, Kind.FIELD, sev,
                    missing.isEmpty() ? Status.OK : Status.MISSING,
                    missing.isEmpty() ? "All line fields present." : "Absent on the line: " + missing));
        } catch (Exception e) {
            checks.add(new CapabilityCheck(label, Kind.FIELD, sev, Status.UNKNOWN,
                    "Could not inspect: " + e.getMessage()));
        }
    }

    /** Ask ERPNext whether the integration user holds a permission — read-only, and the real risk. */
    @SuppressWarnings("unchecked")
    private void checkPermission(List<CapabilityCheck> checks, String doctype, String permType,
                                 Severity sev, String note) {
        String label = doctype + " (" + permType + ")";
        try {
            Object res = erp.methodGet("frappe.client.has_permission",
                    Map.of("doctype", doctype, "docname", "", "perm_type", permType));
            Boolean granted = null;
            if (res instanceof Map<?, ?> m && m.get("has_permission") instanceof Boolean b) granted = b;
            if (granted == null) {
                checks.add(new CapabilityCheck(label, Kind.ACCESS, sev, Status.UNKNOWN,
                        "Permission could not be read. " + note));
                return;
            }
            checks.add(new CapabilityCheck(label, Kind.ACCESS, sev,
                    granted ? Status.OK : Status.MISSING,
                    granted ? note : "The integration user lacks '" + permType + "' on " + doctype + ". " + note));
        } catch (Exception e) {
            checks.add(new CapabilityCheck(label, Kind.ACCESS, sev, Status.UNKNOWN,
                    "Could not be checked: " + e.getMessage() + ". " + note));
        }
    }

    /**
     * A network/transport failure says nothing about the ERP's capabilities, so it must not fail one.
     *
     * <p>Deliberately narrow: {@code getListStrict} labels <em>every</em> failure it raises
     * "transport error", so matching that phrase turned genuine 403s and unknown fields into UNKNOWN
     * — which never fails a verdict, and would have made the probe certify anything.
     */
    private static boolean transportOnly(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        if (m.contains("403") || m.contains("404") || m.contains("permission")) return false;
        return m.contains("timeout") || m.contains("timed out") || m.contains("connection refused")
                || m.contains("unreachable") || m.contains("unknownhost");
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
