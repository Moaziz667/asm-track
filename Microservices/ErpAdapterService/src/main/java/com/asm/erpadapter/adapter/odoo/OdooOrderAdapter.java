package com.asm.erpadapter.adapter.odoo;


import com.asm.erpadapter.port.ErpOrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Odoo implementation of ErpOrderPort.
 *
 * Handles order reference resolution.
 * Clients must already exist in Odoo — no partner creation here.
 * Delegates to {@link OdooSaleOrderService} for order ID resolution.
 */
@Component("odooOrder")
@RequiredArgsConstructor
@Slf4j
public class OdooOrderAdapter implements ErpOrderPort {

    private final OdooJsonRpcClient rpc;
    private final OdooSaleOrderService saleOrderService;

    @Override
    public String resolveOrderId(String erpOrderRef) {
        Integer resolved = saleOrderService.resolveErpId(erpOrderRef);
        return resolved != null ? String.valueOf(resolved) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String getOrderReference(String erpOrderId) {
        try {
            Integer id = Integer.parseInt(erpOrderId);
            Map<String, Object> kwargs = Map.of("fields", List.of("id", "name"), "limit", 1);
            Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "search_read",
                    List.of(List.of(List.of("id", "=", id))), kwargs));
            if (response == null || response.containsKey("error")) return null;
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
            if (rows == null || rows.isEmpty()) return null;
            Object name = rows.get(0).get("name");
            return name != null ? String.valueOf(name) : null;
        } catch (Exception e) {
            log.warn("getOrderReference failed for erpOrderId={}", erpOrderId, e);
            return null;
        }
    }
}
