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
 * Handles the version-specific return picking creation flow.
 *
 * <p>Odoo 18+ renamed {@code create_returns} → {@code action_create_returns}.
 * This handler uses the {@link CapabilityResolver} to resolve the correct method
 * and tries each candidate in order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReturnHandler {

    private final OdooJsonRpcClient rpc;
    private final CapabilityResolver capabilityResolver;

    /**
     * Call the create-returns method on the stock.return.picking wizard.
     * Tries each candidate method from the capability registry until one succeeds.
     *
     * @param wizardId the stock.return.picking wizard ID
     * @param ctx      the Odoo context to pass
     * @return the Odoo RPC response, or the last error if all candidates failed
     */
    public Map<String, Object> callCreateReturns(Integer wizardId, Map<String, Object> ctx) {
        List<String> candidates = capabilityResolver.getCandidates(CanonicalCapability.CREATE_RETURN);
        Map<String, Object> resp = null;
        for (String method : candidates) {
            resp = rpc.callRpc(rpc.buildArgs("stock.return.picking", method,
                    List.of(List.of(wizardId)), Map.of("context", ctx)));
            if (resp != null && !resp.containsKey("error")) {
                log.info("provider=odoo operation=createReturns wizardId={} method={} action=ok", wizardId, method);
                return resp;
            }
            log.info("provider=odoo operation=createReturns wizardId={} method={} action=fallback error={}",
                    wizardId, method, resp != null ? resp.get("error") : "null_response");
        }
        return resp;
    }
}
