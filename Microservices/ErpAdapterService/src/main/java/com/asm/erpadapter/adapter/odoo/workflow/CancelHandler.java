package com.asm.erpadapter.adapter.odoo.workflow;

import com.asm.erpadapter.adapter.odoo.CanonicalCapability;
import com.asm.erpadapter.adapter.odoo.CapabilityResolver;
import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Handles the version-specific cancel flow for sale orders.
 *
 * <p>Odoo 19 auto-locks confirmed orders — {@code action_unlock} must be called
 * before {@code action_cancel}. On Odoo 16-18, {@code action_unlock} is a no-op
 * (safe to call always).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelHandler {

    private final OdooJsonRpcClient rpc;
    private final CapabilityResolver capabilityResolver;

    /**
     * Cancel a sale order with unlock-before-cancel for Odoo 19 compatibility.
     *
     * @param erpOrderId the Odoo sale.order ID
     * @return true if the order reached 'cancel' state
     * @throws SaleOrderNotFoundException if the order does not exist in Odoo (non-retryable)
     */
    public boolean cancelSaleOrder(Integer erpOrderId) {
        // Odoo 19 auto-locks confirmed orders — unlock first (no-op on ≤18)
        String unlockMethod = capabilityResolver.resolve(CanonicalCapability.UNLOCK_SALE_ORDER);
        rpc.callRpc(rpc.buildArgs("sale.order", unlockMethod, List.of(List.of(erpOrderId))));
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "action_cancel",
                List.of(List.of(erpOrderId))));
        if (resp != null && resp.containsKey("error")) {
            String errorMsg = OdooJsonRpcClient.extractOdooErrorMessage(resp.get("error"));
            if (errorMsg != null && (
                    errorMsg.contains("does not exist") ||
                    errorMsg.contains("not found") ||
                    errorMsg.contains("Missing record"))) {
                throw new SaleOrderNotFoundException(erpOrderId);
            }
            log.warn("ERP sync failed — provider=odoo operation=cancelSaleOrder erpId={} odooError={} retryable=true",
                    erpOrderId, resp.get("error"));
            return false;
        }
        String state = rpc.readRecordState("sale.order", erpOrderId);
        log.info("provider=odoo operation=cancelSaleOrder erpId={} finalState={}", erpOrderId, state);
        return "cancel".equalsIgnoreCase(state);
    }
}
