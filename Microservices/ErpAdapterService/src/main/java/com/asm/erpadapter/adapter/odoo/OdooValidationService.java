package com.asm.erpadapter.adapter.odoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Reusable Odoo picking validation and wizard-handling operations.
 *
 * <p>Encapsulates the complex {@code button_validate} flow including its three possible wizard
 * outcomes (immediate transfer, backorder confirmation, SMS confirmation) across Odoo 16→19.
 * Extracted from {@link OdooSyncAdapter} so that full, partial, and return workflows all share
 * the same validated code path.
 *
 * <p>Stateless — all Odoo state is read/written via {@link OdooJsonRpcClient}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooValidationService {

    private final OdooJsonRpcClient rpc;
    private final OdooPickingService pickingService;
    private final OdooCapabilities caps;

    // ── Full validation (by sale order) ───────────────────────────────────────

    /**
     * Validate a picking for a full delivery. Resolves the picking from the sale order
     * (or uses an explicit picking ID), reserves stock, sets full quantities, and calls
     * {@code button_validate} with wizard handling.
     *
     * @param erpOrderId       the Odoo sale.order ID
     * @param explicitPickingId optional — when set, skips the sale order → picking resolution
     * @return true if the picking reaches 'done' state
     */
    public boolean validateTransfer(Integer erpOrderId, Integer explicitPickingId) {
        pickingService.findSinglePicking(erpOrderId); // confirm order first
        confirmOrderIfNeeded(erpOrderId);

        Map<String, Object> picking = (explicitPickingId != null)
                ? pickingService.findPickingById(explicitPickingId)
                : pickingService.findSinglePicking(erpOrderId);

        if (picking == null) {
            if (pickingService.hasDonePicking(erpOrderId)) {
                log.info("provider=odoo operation=validateTransfer erpOrderId={} state=already_done action=idempotent_success", erpOrderId);
                return true;
            }
            log.warn("ERP sync failed — provider=odoo operation=validateTransfer erpOrderId={} reason=no_picking_found retryable=false",
                    erpOrderId);
            return false;
        }

        Integer pickingId = ((Number) picking.get("id")).intValue();
        if ("done".equals(picking.get("state"))) return true;

        return doValidateTransfer(erpOrderId, pickingId);
    }

    // ── Validation by picking ID ──────────────────────────────────────────────

    /**
     * Validate a picking directly by its Odoo ID — used for backorder deliveries
     * and return pickings where the picking ID is already known.
     */
    public boolean validateTransferByPickingId(Integer pickingId) {
        Map<String, Object> picking = pickingService.findPickingById(pickingId);
        if (picking == null) {
            log.warn("ERP sync failed — provider=odoo operation=validateTransferByPickingId pickingId={} reason=picking_not_found retryable=false",
                    pickingId);
            return false;
        }
        if ("done".equals(picking.get("state"))) return true;

        return doValidateTransfer(null, pickingId);
    }

    // ── Reserve stock ─────────────────────────────────────────────────────────

    /**
     * Trigger stock reservation on a picking ({@code action_assign}).
     */
    public void reserveStock(Integer pickingId) {
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", "action_assign",
                List.of(List.of(pickingId))));
        Object error = resp != null ? resp.get("error") : null;
        if (error != null) {
            log.warn("provider=odoo operation=reserveStock pickingId={} odooError={}", pickingId, error);
        } else {
            log.info("provider=odoo operation=reserveStock pickingId={} result={}", pickingId,
                    resp != null ? resp.get("result") : "null");
        }
    }

    // ── Force availability ────────────────────────────────────────────────────

    /**
     * Force availability when stock reservation fails ({@code action_force_availability}).
     */
    public void forceAvailability(Integer pickingId) {
        log.info("provider=odoo operation=forceAvailability pickingId={}", pickingId);
        rpc.callRpc(rpc.buildArgs("stock.picking", "action_force_availability", List.of(List.of(pickingId))));
    }

    // ── Set full quantities ───────────────────────────────────────────────────

    /**
     * Set qty_done = reserved_qty on all move lines ({@code action_set_quantities_to_reservation}).
     * Works on Odoo 16, 17, and 18 without field-name version differences.
     */
    public void setFullQuantityDoneOnMoveLines(Integer pickingId) {
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(
                "stock.picking", "action_set_quantities_to_reservation", List.of(List.of(pickingId))));
        log.info("provider=odoo operation=setFullQuantityDone pickingId={} result={}",
                pickingId, resp != null ? resp.get("result") : "null");
    }

    // ── Confirm sale order ────────────────────────────────────────────────────

    /**
     * Read the sale order state and confirm only when it is still a draft/sent quotation.
     * Re-running action_confirm on an already-confirmed order is at best a no-op and at
     * worst throws on some Odoo versions.
     */
    public void confirmOrderIfNeeded(Integer erpOrderId) {
        String state = readSaleOrderState(erpOrderId);
        if ("draft".equalsIgnoreCase(state) || "sent".equalsIgnoreCase(state)) {
            rpc.callRpc(rpc.buildArgs("sale.order", "action_confirm", List.of(List.of(erpOrderId))));
        }
    }

    /**
     * Read the state of a sale order.
     */
    public String readSaleOrderState(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "read",
                List.of(List.of(erpOrderId), List.of("state"))));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? (String) result.get(0).get("state") : null;
    }

    // ── Private implementation ────────────────────────────────────────────────

    /**
     * Core validation flow: reserve stock → set quantities → button_validate → handle wizard.
     * Handles the three possible wizard outcomes across Odoo 16→19.
     */
    private boolean doValidateTransfer(Integer erpOrderId, Integer pickingId) {
        pickingService.reserveStock(pickingId);

        String stateAfterReserve = pickingService.readPickingState(pickingId);
        if ("confirmed".equalsIgnoreCase(stateAfterReserve)) {
            log.info("provider=odoo operation=doValidateTransfer pickingId={} state=confirmed action=force_availability",
                    pickingId);
            forceAvailability(pickingId);
        }

        setFullQuantityDoneOnMoveLines(pickingId);

        // Check if picking has any stock.move records — service products have none
        Map<String, Object> movesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                Map.of("fields", List.of("id", "state", "product_id", "product_uom_qty"), "limit", 1)));
        List<?> moves = movesResp != null ? (List<?>) movesResp.get("result") : null;
        if (moves == null || moves.isEmpty()) {
            log.info("provider=odoo operation=doValidateTransfer pickingId={} info=no_stock_moves_service_order action=skip_validation",
                    pickingId);
            return true;
        }

        // skip_sms=True bypasses the confirm.stock.sms wizard in Odoo 17/18
        Map<String, Object> validateResp = rpc.callRpc(
                rpc.buildArgs("stock.picking", "button_validate",
                        List.of(List.of(pickingId)),
                        Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));

        Object rawResult = validateResp != null ? validateResp.get("result") : null;
        if (rawResult instanceof Map<?, ?> res) {
            handleWizard(pickingId, res);
        }

        String finalState = pickingService.readPickingState(pickingId);
        if (!"done".equalsIgnoreCase(finalState)) {
            log.warn("ERP sync failed — provider=odoo operation=doValidateTransfer erpOrderId={} pickingId={} finalState={} reason=picking_not_done retryable=true",
                    erpOrderId, pickingId, finalState);
            return false;
        }
        return true;
    }

    // ── Wizard handling ───────────────────────────────────────────────────────

    /**
     * Handle the wizard returned by {@code button_validate}. Dispatches to the appropriate
     * handler based on {@code res_model}.
     */
    void handleWizard(Integer pickingId, Map<?, ?> res) {
        String resModel = (String) res.get("res_model");
        log.info("provider=odoo operation=handleWizard pickingId={} wizard={}", pickingId, resModel);
        if ("stock.immediate.transfer".equals(resModel)) {
            confirmImmediateTransferWizard(res);
        } else if ("stock.backorder.confirmation".equals(resModel)) {
            confirmBackorderWizard(res);
        } else if ("confirm.stock.sms".equals(resModel)) {
            confirmSmsWizard(res);
        } else {
            log.warn("provider=odoo operation=handleWizard pickingId={} unhandled_wizard={} result={}",
                    pickingId, resModel, res);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Raw button_validate call — used by partial delivery workflow that handles
     * the wizard response itself.
     */
    public Map<String, Object> callValidatePicking(Integer pickingId) {
        return rpc.callRpc(rpc.buildArgs("stock.picking", "button_validate",
                List.of(List.of(pickingId)),
                Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));
    }
}
