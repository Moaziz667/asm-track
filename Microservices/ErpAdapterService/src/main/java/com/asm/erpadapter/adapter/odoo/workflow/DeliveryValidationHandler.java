package com.asm.erpadapter.adapter.odoo.workflow;

import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import com.asm.erpadapter.adapter.odoo.OdooWorkflowException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles version-specific wizard behavior from {@code button_validate}.
 *
 * <p>Extracts the unstable Odoo workflow logic that differs across versions:
 * <ul>
 *   <li>Backorder wizard: Odoo 16-17 (wizard in DB) vs Odoo 18-19 (wizard in memory)</li>
 *   <li>SMS confirmation: try {@code action_confirm} (v17), fallback to {@code action_send_and_validate}</li>
 *   <li>Immediate transfer: null-check on {@code res_id}</li>
 * </ul>
 *
 * <p>Generic validation logic (reserve stock, set quantities, button_validate call)
 * stays in {@link com.asm.erpadapter.adapter.odoo.OdooValidationService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryValidationHandler {

    private final OdooJsonRpcClient rpc;

    /**
     * Handle the wizard returned by {@code button_validate}. Dispatches to the appropriate
     * handler based on {@code res_model}.
     */
    public void handleWizard(Integer pickingId, Map<?, ?> res) {
        String resModel = (String) res.get("res_model");
        log.info("provider=odoo operation=handleWizard pickingId={} wizard={}", pickingId, resModel);
        if ("stock.immediate.transfer".equals(resModel)) {
            confirmImmediateTransferWizard(res);
        } else if ("stock.backorder.confirmation".equals(resModel)) {
            confirmBackorderWizard(res);
        } else if ("confirm.stock.sms".equals(resModel)) {
            confirmSmsWizard(res);
        } else {
            log.error("provider=odoo operation=handleWizard pickingId={} unhandled_wizard={} result={}",
                    pickingId, resModel, res);
            throw new OdooWorkflowException("handleWizard",
                    "Unknown wizard type '" + resModel + "' for picking " + pickingId);
        }
    }

    /**
     * Confirm the SMS wizard — "Validate without SMS".
     * Tries {@code action_confirm} first (Odoo 17), falls back to {@code action_send_and_validate}.
     */
    void confirmSmsWizard(Map<?, ?> res) {
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(
                        "confirm.stock.sms", "action_confirm", List.of(List.of(wid.intValue()))));
                if (resp != null && resp.containsKey("error")) {
                    rpc.callRpc(rpc.buildArgs(
                            "confirm.stock.sms", "action_send_and_validate", List.of(List.of(wid.intValue()))));
                }
                log.info("provider=odoo operation=confirmSmsWizard wizardId={} action=confirmed_no_sms", wid.intValue());
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmSmsWizard reason={}", e.getMessage());
        }
    }

    /**
     * Confirm the immediate transfer wizard — {@code stock.immediate.transfer.process}.
     */
    void confirmImmediateTransferWizard(Map<?, ?> res) {
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                rpc.callRpc(rpc.buildArgs("stock.immediate.transfer", "process",
                        List.of(List.of(wid.intValue()))));
                log.info("provider=odoo operation=confirmImmediateTransferWizard wizardId={} action=confirmed",
                        wid.intValue());
            } else {
                log.warn("provider=odoo operation=confirmImmediateTransferWizard reason=no_res_id res={}", res);
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmImmediateTransferWizard reason={}", e.getMessage());
        }
    }

    /**
     * Confirm the backorder wizard — {@code stock.backorder.confirmation.process}.
     * Handles Odoo 16/17 (wizard record exists, just call process) and
     * Odoo 18/19 (returns ir.actions.act_window, must create wizard manually).
     */
    @SuppressWarnings("unchecked")
    void confirmBackorderWizard(Map<?, ?> res) {
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                // Odoo 16/17: wizard record already exists, just call process
                rpc.callRpc(rpc.buildArgs("stock.backorder.confirmation", "process",
                        List.of(List.of(wid.intValue()))));
                log.info("provider=odoo operation=confirmBackorderWizard wizardId={} action=confirmed",
                        wid.intValue());
                return;
            }
            // Odoo 18/19: returns ir.actions.act_window with no res_id — create the wizard ourselves
            Object ctxObj = res.get("context");
            List<Integer> pickingIds = null;
            if (ctxObj instanceof Map<?, ?> ctx) {
                Object bvIds = ctx.get("button_validate_picking_ids");
                if (bvIds instanceof List<?> list && !list.isEmpty()) {
                    pickingIds = (List<Integer>) list;
                }
            }
            if (pickingIds == null || pickingIds.isEmpty()) {
                log.warn("provider=odoo operation=confirmBackorderWizard reason=no_picking_ids_in_context res={}",
                        res);
                return;
            }
            // Create wizard with pick_ids AND backorder_confirmation_line_ids
            List<List<Object>> pickIdsCmd = new ArrayList<>();
            List<List<Object>> linesCmd = new ArrayList<>();
            for (Integer pid : pickingIds) {
                pickIdsCmd.add(List.of(4, pid));
                linesCmd.add(List.of(0, 0, Map.of("picking_id", pid, "to_backorder", true)));
            }
            Map<String, Object> wizardVals = new java.util.HashMap<>();
            wizardVals.put("pick_ids", pickIdsCmd);
            wizardVals.put("backorder_confirmation_line_ids", linesCmd);
            Map<String, Object> createResp = rpc.callRpc(rpc.buildArgs(
                    "stock.backorder.confirmation", "create", List.of(wizardVals)));
            Object wizardId = createResp != null ? createResp.get("result") : null;
            if (wizardId instanceof Number wid2) {
                Map<String, Object> processResp = rpc.callRpc(rpc.buildArgs(
                        "stock.backorder.confirmation", "process",
                        List.of(List.of(wid2.intValue())),
                        Map.of("context", Map.of("button_validate_picking_ids", pickingIds, "skip_sms", true))));
                if (processResp != null && processResp.containsKey("error")) {
                    log.warn("provider=odoo operation=confirmBackorderWizard wizardId={} process_error={}",
                            wid2.intValue(), processResp.get("error"));
                } else {
                    log.info("provider=odoo operation=confirmBackorderWizard wizardId={} pickingIds={} process_result={} action=confirmed_odoo19",
                            wid2.intValue(), pickingIds,
                            processResp != null ? processResp.get("result") : "null");
                }
            } else {
                log.warn("provider=odoo operation=confirmBackorderWizard reason=create_failed wizardId={}",
                        wizardId);
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmBackorderWizard reason={}", e.getMessage());
        }
    }
}
