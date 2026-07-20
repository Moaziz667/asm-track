package com.asm.erpadapter.adapter.odoo;

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
 * Odoo implementation of {@link ErpChangePort}: polls {@code sale.order} for rows whose {@code write_date}
 * is newer than the cursor. Registered under the provider key {@code "odoo"} (class-name prefix) so
 * {@code ErpProviderRouter} hands it to Odoo tenants only. The connection is read per-tenant from
 * {@code SettingsClient} inside {@link OdooJsonRpcClient}, so setting the TenantContext is enough.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooChangeAdapter implements ErpChangePort {

    private static final DateTimeFormatter ODOO_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OdooJsonRpcClient rpc;

    /** Odoo stores write_date in UTC; the cursor uses the same format/zone. */
    @Override
    public String initialCursor() {
        return LocalDateTime.now(ZoneOffset.UTC).format(ODOO_TS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ErpOrderChangeDTO> fetchChanges(String sinceCursor, int limit) {
        List<ErpOrderChangeDTO> out = new ArrayList<>();
        try {
            List<List<Object>> domain = List.of(List.of("write_date", ">", sinceCursor));
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "search_read",
                    List.of(domain),
                    Map.of("fields", List.of("id", "name", "state", "write_date", "commitment_date"),
                           "order", "write_date asc", "limit", limit)));
            List<Map<String, Object>> orders = resp != null ? (List<Map<String, Object>>) resp.get("result") : null;
            if (orders == null) return out;

            for (Map<String, Object> o : orders) {
                String ref = OdooJsonRpcClient.asString(o.get("name"));
                String writeDate = OdooJsonRpcClient.asString(o.get("write_date"));
                if (ref == null) continue;
                String state = OdooJsonRpcClient.asString(o.get("state"));

                if ("cancel".equalsIgnoreCase(state)) {
                    out.add(new ErpOrderChangeDTO(ref, "CANCELLED", null, writeDate));
                } else {
                    // Generic content change: forward a DATE snapshot (commitment_date). The platform's
                    // frontier rule + anti-replay decide whether/how to apply it.
                    Map<String, Object> payload = new HashMap<>();
                    String commitmentDate = OdooJsonRpcClient.asString(o.get("commitment_date")); // asString maps Odoo `false` → null
                    if (commitmentDate != null) payload.put("scheduledAt", commitmentDate);
                    if (!payload.isEmpty()) out.add(new ErpOrderChangeDTO(ref, "DATE", payload, writeDate));
                }
            }
        } catch (Exception e) {
            log.warn("Odoo change fetch failed (will retry next tick): {}", e.getMessage());
        }
        return out;
    }
}
