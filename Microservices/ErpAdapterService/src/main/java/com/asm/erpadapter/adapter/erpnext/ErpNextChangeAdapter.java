package com.asm.erpadapter.adapter.erpnext;

import com.asm.tenant.TenantContext;

import com.asm.erpadapter.dto.ErpOrderChangeDTO;
import com.asm.erpadapter.port.ErpChangePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ERPNext implementation of {@link ErpChangePort}: lists {@code Sales Order} documents whose Frappe
 * {@code modified} timestamp is newer than the cursor. Registered under the provider key {@code "erpnext"}
 * (class-name prefix) so {@code ErpProviderRouter} hands it to ERPNext tenants — the Odoo poller is never
 * used for them and vice-versa. Connection read per-tenant from {@code SettingsClient} inside
 * {@link ErpNextRestClient}, so setting the TenantContext is enough.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpNextChangeAdapter implements ErpChangePort {

    private static final DateTimeFormatter FRAPPE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ErpNextRestClient rest;

    /**
     * Statuses an order reaches by being delivered or billed, rather than by being re-planned.
     *
     * <p>DeliveryService compares the announced value against what it stored and drops anything
     * identical, so this is belt to that braces — but it also spares a pointless round trip per
     * completed delivery, and keeps the debug log readable when something really does change.
     */
    private static boolean isDeliveryProgress(String status) {
        if (status == null) return false;
        return switch (status) {
            case "Completed", "To Bill", "Closed" -> true;
            default -> false;
        };
    }

    @Override
    public String initialCursor() {
        // Frappe `modified` is a "YYYY-MM-DD HH:MM:SS[.ffffff]" timestamp; seed with now so the first
        // poll doesn't replay history. A small startup tz skew self-corrects — later cursors are real
        // `modified` values and the platform de-duplicates by cursor token.
        return LocalDateTime.now(ZoneOffset.UTC).format(FRAPPE_TS);
    }

    @Override
    public List<ErpOrderChangeDTO> fetchChanges(String sinceCursor, int limit) {
        List<ErpOrderChangeDTO> out = new ArrayList<>();
        try {
            List<List<Object>> filters = List.of(List.of("modified", ">", sinceCursor));
            List<Map<String, Object>> rows = rest.getList("Sales Order",
                    List.of("name", "status", "docstatus", "modified", "delivery_date"),
                    filters, limit, "modified asc");

            for (Map<String, Object> so : rows) {
                String ref = ErpNextRestClient.asString(so.get("name"));
                String modified = ErpNextRestClient.asString(so.get("modified"));
                if (ref == null) continue;

                Integer docstatus = ErpNextRestClient.asInt(so.get("docstatus"));
                String status = ErpNextRestClient.asString(so.get("status"));
                boolean cancelled = (docstatus != null && docstatus == 2) || "Cancelled".equalsIgnoreCase(status);

                if (cancelled) {
                    out.add(new ErpOrderChangeDTO(ref, "CANCELLED", null, modified));
                    continue;
                }

                // Delivering is not a change of plan.
                //
                // Frappe moves `modified` whenever anything on the document is touched, and the
                // thing that touches it most often is us: submitting a delivery note updates the
                // order's delivered quantities and its status. Announcing that as a change made ASM
                // report a conflict on every delivery it had just completed itself.
                //
                // The delivery lifecycle statuses are therefore skipped. A real reschedule leaves the
                // order in "To Deliver and Bill" / "To Deliver", where it still is one.
                if (isDeliveryProgress(status)) {
                    log.debug("[erpnext] so={} modified into status '{}' — delivery progress, not a plan change",
                            ref, status);
                    continue;
                }

                Map<String, Object> payload = new HashMap<>();
                String deliveryDate = ErpNextRestClient.asString(so.get("delivery_date"));
                if (deliveryDate != null) payload.put("scheduledAt", deliveryDate);
                if (!payload.isEmpty()) out.add(new ErpOrderChangeDTO(ref, "DATE", payload, modified));
            }
        } catch (Exception e) {
            log.warn("ERPNext change fetch failed (will retry next tick): {}", e.getMessage());
        }
        return out;
    }
}
