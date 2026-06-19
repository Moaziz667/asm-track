package com.asm.erpadapter.inbound;

import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 — Polling channel for Odoo→ASM changes (the inbound sync mechanism; the real-time webhook channel
 * was removed). Every {@code N} minutes it asks Odoo for sale orders whose {@code write_date} is newer
 * than the last cursor and forwards each to ASM (which de-duplicates via write_date vs lastSyncedAt).
 *
 * <p><b>Pluggability note:</b> this poller is Odoo-specific (it queries the {@code sale.order} model
 * via {@link OdooJsonRpcClient}). When a second ERP (DUX) implements outbound changes, extract an
 * {@code ErpChangePort} with per-provider pollers selected like {@code ErpSyncPort}. Until DUX is
 * implemented this single Odoo poller is sufficient.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "erp.default-provider", havingValue = "odoo", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ErpChangePoller {

    private final OdooJsonRpcClient rpc;
    private final ErpChangeForwarder forwarder;

    @Value("${erp.inbound.poll.enabled:true}")
    private boolean enabled;

    /** Cursor: Odoo write_date "YYYY-MM-DD HH:MM:SS" of the last polled change. */
    private volatile String cursor;

    @Scheduled(fixedDelayString = "${erp.inbound.poll.fixed-delay-ms:600000}", initialDelay = 60000)
    @SuppressWarnings("unchecked")
    public void pollChangedOrders() {
        if (!enabled) return;
        try {
            // First run: set the cursor to "now" so we don't replay the whole history; from then on we
            // only forward genuinely new changes.
            if (cursor == null) {
                cursor = serverNow();
                log.info("ERP change poller: cursor initialized to {}", cursor);
                return;
            }

            List<List<Object>> domain = List.of(List.of("write_date", ">", cursor));
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "search_read",
                    List.of(domain),
                    Map.of("fields", List.of("id", "name", "state", "write_date", "commitment_date"),
                           "order", "write_date asc", "limit", 100)));
            List<Map<String, Object>> orders = resp != null ? (List<Map<String, Object>>) resp.get("result") : null;
            if (orders == null || orders.isEmpty()) return;

            for (Map<String, Object> o : orders) {
                String ref = str(o.get("name"));
                String state = str(o.get("state"));
                String writeDate = str(o.get("write_date"));
                if (ref == null) continue;

                if ("cancel".equalsIgnoreCase(state)) {
                    forwarder.forward(ref, "CANCELLED", null, writeDate);
                } else {
                    // Generic content change: forward a DATE snapshot (commitment_date). ASM's frontier
                    // rule + anti-replay decide whether/how to apply. (Line-level diffing can be added later.)
                    Map<String, Object> payload = new HashMap<>();
                    // Odoo returns boolean `false` for an unset field — skip it, don't forward "false".
                    Object commitmentDate = o.get("commitment_date");
                    if (commitmentDate != null && !Boolean.FALSE.equals(commitmentDate)
                            && !"false".equalsIgnoreCase(String.valueOf(commitmentDate))) {
                        payload.put("scheduledAt", str(commitmentDate));
                    }
                    if (!payload.isEmpty()) forwarder.forward(ref, "DATE", payload, writeDate);
                }
                if (writeDate != null) cursor = writeDate; // advance cursor (orders are write_date asc)
            }
            log.info("ERP change poller: forwarded {} changed order(s), cursor now {}", orders.size(), cursor);
        } catch (Exception e) {
            log.warn("ERP change poller failed (will retry next tick): {}", e.getMessage());
        }
    }

    /** Odoo stores write_date in UTC; the cursor uses the same format/zone. */
    private String serverNow() {
        return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }
}
