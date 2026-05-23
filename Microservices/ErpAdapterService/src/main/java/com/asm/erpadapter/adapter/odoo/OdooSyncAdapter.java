package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Odoo implementation of {@link ErpSyncPort}.
 * Refactored for 'Exactly-Once' delivery using IdempotencyService.
 */
@Component("odoo")
@RequiredArgsConstructor
@Slf4j
public class OdooSyncAdapter implements ErpSyncPort {

    private final OdooJsonRpcClient rpc;
    private final IdempotencyService idempotency;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile Integer deliveryFailedTagId;

    @Override
    public boolean syncOrderCancellation(String erpOrderId, String transactionId) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            if (!inFlight.add(erpOrderId)) return false;
            try { return doSyncOrderCancellation(erpOrderId); }
            finally { inFlight.remove(erpOrderId); }
        });
    }

    @Override
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            if (!inFlight.add(erpOrderId)) return false;
            try { return doSyncFullDelivery(erpOrderId, backorderPickingId); }
            finally { inFlight.remove(erpOrderId); }
        });
    }

    @Override
    public ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items, String transactionId) {
        return idempotency.execute(transactionId, erpOrderId, ErpPartialDeliveryResultDTO.class, () -> {
            if (!inFlight.add(erpOrderId)) return ErpPartialDeliveryResultDTO.builder().success(false).build();
            try { return doSyncPartialDelivery(erpOrderId, items); }
            finally { inFlight.remove(erpOrderId); }
        });
    }

    @Override
    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            if (!inFlight.add(erpOrderId)) return false;
            try { return doSyncFailure(erpOrderId, failureCode, comment); }
            finally { inFlight.remove(erpOrderId); }
        });
    }


    // ══════════════════════════════════════════════════════════════════════════
    //  Core Sync Logic (Internal)
    // ══════════════════════════════════════════════════════════════════════════

    private boolean doSyncOrderCancellation(String erpOrderId) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;
        
        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            success = cancelSaleOrder(erpId);
            attempts++;
        }
        return success;
    }

    private boolean doSyncFullDelivery(String erpOrderId, Integer backorderPickingId) {
        // Backorder path: when we already know the Odoo picking ID, skip the sale order lookup.
        if (backorderPickingId != null) {
            try {
                boolean transferOk = validateTransferByPickingId(backorderPickingId);
                if (!transferOk) {
                    log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery (backorder) erpOrderId={} backorderPickingId={} reason=transfer_validation_failed retryable=true",
                            erpOrderId, backorderPickingId);
                    return false;
                }
                return true;
            } catch (Exception e) {
                log.error("ERP sync exception — provider=odoo operation=syncFullDelivery (backorder) erpOrderId={} backorderPickingId={} errorClass={} reason={} retryable=true",
                        erpOrderId, backorderPickingId, e.getClass().getSimpleName(), e.getMessage(), e);
                return false;
            }
        }

        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        try {
            boolean transferOk = validateTransfer(erpId, null);
            if (!transferOk) {
                log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery erpOrderId={} erpId={} reason=transfer_validation_failed retryable=true",
                        erpOrderId, erpId);
                return false;
            }
            syncSaleOrderLineDeliveredQuantities(erpId, null, true);
            return true;
        } catch (Exception e) {
            log.error("ERP sync exception — provider=odoo operation=syncFullDelivery erpOrderId={} erpId={} errorClass={} reason={} retryable=true",
                    erpOrderId, erpId, e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }

    private ErpPartialDeliveryResultDTO doSyncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return ErpPartialDeliveryResultDTO.builder().success(false).build();

        try {
            confirmOrder(erpId);
            Map<String, Object> picking = findSinglePicking(erpId);
            if (picking == null) {
                log.warn("ERP sync failed — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} reason=no_picking_found retryable=true",
                        erpOrderId, erpId);
                return ErpPartialDeliveryResultDTO.builder().success(false).build();
            }

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            if ("done".equals(state)) {
                // Retry path: picking already validated — sync qty lines and return success.
                syncSaleOrderLineDeliveredQuantities(erpId, items, false);
                return ErpPartialDeliveryResultDTO.builder()
                        .success(true).pickingId(pickingId).backorderPickingId(findBackorderPickingId(pickingId)).build();
            }

            reserveStock(pickingId);
            // If stock not reservable (already reserved elsewhere), force availability
            String stateAfterReserve = readPickingState(pickingId);
            if (!"assigned".equalsIgnoreCase(stateAfterReserve)) {
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} state={} action=force_availability",
                        pickingId, stateAfterReserve);
                rpc.callRpc(rpc.buildArgs("stock.picking", "action_force_availability", List.of(List.of(pickingId))));
            }
            // Write done quantities to stock.move.line — match by product default_code fetched live from Odoo.
            // Returns total qty_done written across all storable move lines.
            int totalQtyDone = applyPartialQtyDoneToMoveLines(pickingId, items);

            if (totalQtyDone == 0) {
                // All storable items were refused/damaged — nothing to move in stock.
                // Validating a zero-quantity picking throws a UserError in Odoo.
                // Leave the picking open (it becomes the re-delivery picking), just post a note.
                String allRefusedNote = buildPartialDeliveryNote(items);
                if (allRefusedNote != null) addNoteToSaleOrder(erpId, allRefusedNote);
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} action=skip_validation reason=all_qty_zero", pickingId);
                return ErpPartialDeliveryResultDTO.builder().success(true).pickingId(pickingId).backorderPickingId(null).build();
            }

            Map<String, Object> validateResponse = callValidatePicking(pickingId);
            if (validateResponse == null || validateResponse.containsKey("error")) {
                Object odooError = validateResponse != null ? validateResponse.get("error") : "null_response";
                log.warn("ERP sync failed — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} pickingId={} odooError={} retryable=true",
                        erpOrderId, erpId, pickingId, odooError);
                return ErpPartialDeliveryResultDTO.builder().success(false).build();
            }

            // Handle wizard returned by button_validate.
            // For backorder confirmation: bypass the wizard and call _action_done directly.
            // confirmBackorderWizard(res) calls wizard.process() which calls _action_done internally,
            // but it fails when moves are in 'partially_available' state because process() silently errors.
            // Calling _action_done directly handles partially_available moves correctly.
            if (validateResponse.get("result") instanceof Map<?, ?> res) {
                String resModel = (String) res.get("res_model");
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} wizard={} action=confirming", pickingId, resModel);
                if ("stock.backorder.confirmation".equals(resModel)) {
                    confirmBackorderWizard(res);
                } else if ("confirm.stock.sms".equals(resModel)) {
                    confirmSmsWizard(res);
                } else if ("stock.immediate.transfer".equals(resModel)) {
                    // Do NOT call confirmImmediateTransferWizard here — its process() would fill all
                    // move lines back to the reserved quantity, overriding the qty_done=0 we wrote for
                    // refused/damaged items. Call _action_done directly to validate with our quantities.
                    log.info("provider=odoo operation=syncPartialDelivery pickingId={} wizard=immediate_transfer action=_action_done_direct", pickingId);
                    rpc.callRpc(rpc.buildArgs("stock.picking", "_action_done", List.of(List.of(pickingId))));
                }
            }

            syncSaleOrderLineDeliveredQuantities(erpId, items, false);

            // Post a structured chatter note listing refused/damaged items with reasons
            String partialNote = buildPartialDeliveryNote(items);
            if (partialNote != null) {
                addNoteToSaleOrder(erpId, partialNote);
            }

            Integer backorderPickingId = findBackorderPickingId(pickingId);
            log.info("provider=odoo operation=syncPartialDelivery pickingId={} backorderPickingId={}", pickingId, backorderPickingId);
            return ErpPartialDeliveryResultDTO.builder()
                    .success(true).pickingId(pickingId).backorderPickingId(backorderPickingId).build();
        } catch (Exception e) {
            log.error("ERP sync exception — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} errorClass={} reason={} retryable=true",
                    erpOrderId, erpId, e.getClass().getSimpleName(), e.getMessage(), e);
            return ErpPartialDeliveryResultDTO.builder().success(false).build();
        }
    }

    private boolean doSyncFailure(String erpOrderId, String failureCode, String comment) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        String note = buildFailureNote(failureCode, comment);
        addNoteToSaleOrder(erpId, note);
        tagOrderAsDeliveryFailed(erpId);
        return true;
    }

    private String humanizeReason(String reason) {
        if (reason == null) return "";
        return switch (reason.toUpperCase()) {
            case "CLIENT_ABSENT"   -> "Client absent";
            case "CLIENT_REJECTED" -> "Client a refusé";
            case "DAMAGED"         -> "Endommagé";
            case "WRONG_ITEM"      -> "Mauvais article";
            case "POSTPONED"       -> "Reporté";
            default                -> reason;
        };
    }

    private String buildFailureNote(String failureCode, String comment) {
        StringBuilder sb = new StringBuilder("<b>ASM Track — Livraison échouée</b><br/>");
        sb.append("<b>Code :</b> ").append(failureCode != null ? failureCode : "UNKNOWN").append("<br/>");
        if (comment != null && !comment.isBlank()) {
            sb.append("<b>Commentaire :</b> ").append(comment);
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Odoo JSON-RPC Low-Level (Private)
    // ══════════════════════════════════════════════════════════════════════════

    private void confirmOrder(Integer erpOrderId) {
        rpc.callRpc(rpc.buildArgs("sale.order", "action_confirm", List.of(List.of(erpOrderId))));
    }

    private boolean cancelSaleOrder(Integer erpOrderId) {
        // Odoo 19 auto-locks confirmed orders — unlock before cancelling.
        rpc.callRpc(rpc.buildArgs("sale.order", "action_unlock", List.of(List.of(erpOrderId))));

        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "action_cancel", List.of(List.of(erpOrderId))));
        if (resp != null && resp.containsKey("error")) {
            log.warn("ERP sync failed — provider=odoo operation=cancelSaleOrder erpId={} odooError={} retryable=true",
                    erpOrderId, resp.get("error"));
            return false;
        }

        String state = readSaleOrderState(erpOrderId);
        log.info("provider=odoo operation=cancelSaleOrder erpId={} finalState={}", erpOrderId, state);
        return "cancel".equalsIgnoreCase(state);
    }


    private boolean validateTransfer(Integer erpOrderId, Integer explicitPickingId) {
        confirmOrder(erpOrderId);
        Map<String, Object> picking = (explicitPickingId != null)
                ? findPickingById(explicitPickingId)
                : findSinglePicking(erpOrderId);

        if (picking == null) {
            // No pending picking — check if one is already done (idempotent success)
            if (hasDonePicking(erpOrderId)) {
                log.info("provider=odoo operation=validateTransfer erpOrderId={} state=already_done action=idempotent_success", erpOrderId);
                return true;
            }
            log.warn("ERP sync failed — provider=odoo operation=validateTransfer erpOrderId={} reason=no_picking_found retryable=false",
                    erpOrderId);
            return false;
        }

        Integer pickingId = ((Number) picking.get("id")).intValue();
        if ("done".equals(picking.get("state"))) return true;

        reserveStock(pickingId);
        // If stock not available, force availability so move lines are created
        String stateAfterReserve = readPickingState(pickingId);
        if ("confirmed".equalsIgnoreCase(stateAfterReserve)) {
            log.info("provider=odoo operation=validateTransfer pickingId={} state=confirmed action=force_availability", pickingId);
            rpc.callRpc(rpc.buildArgs("stock.picking", "action_force_availability", List.of(List.of(pickingId))));
        }
        setFullQuantityDoneOnMoveLines(pickingId);

        // Check if picking has any stock.move records — service products have none
        Map<String, Object> movesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                Map.of("fields", List.of("id", "state", "product_id", "product_uom_qty"), "limit", 1)));
        List<?> moves = movesResp != null ? (List<?>) movesResp.get("result") : null;
        if (moves == null || moves.isEmpty()) {
            log.info("provider=odoo operation=validateTransfer pickingId={} info=no_stock_moves_service_order action=skip_validation", pickingId);
            return true;
        }

        // skip_sms=True bypasses the confirm.stock.sms wizard in Odoo 17/18
        Map<String, Object> validateResp = rpc.callRpc(
                rpc.buildArgs("stock.picking", "button_validate",
                        List.of(List.of(pickingId)),
                        Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));

        Object rawResult = validateResp != null ? validateResp.get("result") : null;

        if (rawResult instanceof Map<?, ?> res) {
            String resModel = (String) res.get("res_model");
            log.info("provider=odoo operation=validateTransfer pickingId={} wizard={} action=confirming", pickingId, resModel);
            if ("stock.immediate.transfer".equals(resModel)) {
                confirmImmediateTransferWizard(res);
            } else if ("stock.backorder.confirmation".equals(resModel)) {
                confirmBackorderWizard(res);
            } else if ("confirm.stock.sms".equals(resModel)) {
                // skip_sms didn't work on this Odoo version — confirm wizard directly
                confirmSmsWizard(res);
            } else {
                log.warn("provider=odoo operation=validateTransfer pickingId={} unhandled_wizard={} result={}",
                        pickingId, resModel, res);
            }
        }

        String finalState = readPickingState(pickingId);
        if (!"done".equalsIgnoreCase(finalState)) {
            log.warn("ERP sync failed — provider=odoo operation=validateTransfer erpOrderId={} pickingId={} finalState={} reason=picking_not_done retryable=true",
                    erpOrderId, pickingId, finalState);
            return false;
        }
        return true;
    }

    /** Validates a picking directly by its Odoo ID — used for backorder deliveries where the picking ID is already known. */
    private boolean validateTransferByPickingId(Integer pickingId) {
        Map<String, Object> picking = findPickingById(pickingId);
        if (picking == null) {
            log.warn("ERP sync failed — provider=odoo operation=validateTransferByPickingId pickingId={} reason=picking_not_found retryable=false", pickingId);
            return false;
        }
        if ("done".equals(picking.get("state"))) return true;

        reserveStock(pickingId);
        String stateAfterReserve = readPickingState(pickingId);
        if ("confirmed".equalsIgnoreCase(stateAfterReserve)) {
            log.info("provider=odoo operation=validateTransferByPickingId pickingId={} state=confirmed action=force_availability", pickingId);
            rpc.callRpc(rpc.buildArgs("stock.picking", "action_force_availability", List.of(List.of(pickingId))));
        }
        setFullQuantityDoneOnMoveLines(pickingId);

        Map<String, Object> movesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                Map.of("fields", List.of("id"), "limit", 1)));
        List<?> moves = movesResp != null ? (List<?>) movesResp.get("result") : null;
        if (moves == null || moves.isEmpty()) return true;

        Map<String, Object> validateResp = rpc.callRpc(
                rpc.buildArgs("stock.picking", "button_validate",
                        List.of(List.of(pickingId)),
                        Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));
        Object rawResult = validateResp != null ? validateResp.get("result") : null;
        if (rawResult instanceof Map<?, ?> res) {
            String resModel = (String) res.get("res_model");
            log.info("provider=odoo operation=validateTransferByPickingId pickingId={} wizard={} action=confirming", pickingId, resModel);
            if ("stock.immediate.transfer".equals(resModel)) confirmImmediateTransferWizard(res);
            else if ("stock.backorder.confirmation".equals(resModel)) confirmBackorderWizard(res);
            else if ("confirm.stock.sms".equals(resModel)) confirmSmsWizard(res);
        }
        String finalState = readPickingState(pickingId);
        log.info("provider=odoo operation=validateTransferByPickingId pickingId={} finalState={}", pickingId, finalState);
        return "done".equalsIgnoreCase(finalState);
    }

    private boolean hasDonePicking(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("sale_id", "=", erpOrderId), List.of("state", "=", "done"))),
                Map.of("fields", List.of("id"), "limit", 1)));
        List<?> result = (List<?>) response.get("result");
        return result != null && !result.isEmpty();
    }

    private void confirmSmsWizard(Map<?, ?> res) {
        // confirm.stock.sms: "Validate without SMS" = action_confirm in Odoo 17, action_validate in some versions
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                // Try action_confirm first (Odoo 17), fall back to action_send_and_validate
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

    private void confirmImmediateTransferWizard(Map<?, ?> res) {
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                rpc.callRpc(rpc.buildArgs("stock.immediate.transfer", "process",
                        List.of(List.of(wid.intValue()))));
                log.info("provider=odoo operation=confirmImmediateTransferWizard wizardId={} action=confirmed", wid.intValue());
            } else {
                log.warn("provider=odoo operation=confirmImmediateTransferWizard reason=no_res_id res={}", res);
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmImmediateTransferWizard reason={}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void confirmBackorderWizard(Map<?, ?> res) {
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                // Odoo 16/17: wizard record already exists, just call process
                rpc.callRpc(rpc.buildArgs("stock.backorder.confirmation", "process",
                        List.of(List.of(wid.intValue()))));
                log.info("provider=odoo operation=confirmBackorderWizard wizardId={} action=confirmed", wid.intValue());
                return;
            }
            // Odoo 18: returns ir.actions.act_window with no res_id — context has button_validate_picking_ids
            // We must create the wizard record ourselves, then call process.
            Object ctxObj = res.get("context");
            List<Integer> pickingIds = null;
            if (ctxObj instanceof Map<?, ?> ctx) {
                Object bvIds = ctx.get("button_validate_picking_ids");
                if (bvIds instanceof List<?> list && !list.isEmpty()) {
                    pickingIds = (List<Integer>) list;
                }
            }
            if (pickingIds == null || pickingIds.isEmpty()) {
                log.warn("provider=odoo operation=confirmBackorderWizard reason=no_picking_ids_in_context res={}", res);
                return;
            }
            // Create wizard with pick_ids AND backorder_confirmation_line_ids.
            // @api.onchange('pick_ids') does NOT fire via RPC, so we must create the lines
            // explicitly — otherwise process() iterates an empty lines collection and does nothing.
            List<List<Object>> pickIdsCmd = new java.util.ArrayList<>();
            List<List<Object>> linesCmd = new java.util.ArrayList<>();
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
                // Odoo 19: process() reads button_validate_picking_ids from context.
                // Without it, process() returns True immediately and does nothing.
                Map<String, Object> processResp = rpc.callRpc(rpc.buildArgs(
                        "stock.backorder.confirmation", "process",
                        List.of(List.of(wid2.intValue())),
                        Map.of("context", Map.of("button_validate_picking_ids", pickingIds, "skip_sms", true))));
                if (processResp != null && processResp.containsKey("error")) {
                    log.warn("provider=odoo operation=confirmBackorderWizard wizardId={} process_error={}", wid2.intValue(), processResp.get("error"));
                } else {
                    log.info("provider=odoo operation=confirmBackorderWizard wizardId={} pickingIds={} process_result={} action=confirmed_odoo19",
                            wid2.intValue(), pickingIds, processResp != null ? processResp.get("result") : "null");
                }
            } else {
                log.warn("provider=odoo operation=confirmBackorderWizard reason=create_failed wizardId={}", wizardId);
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmBackorderWizard reason={}", e.getMessage());
        }
    }

    private void reserveStock(Integer pickingId) {
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", "action_assign", List.of(List.of(pickingId))));
        Object result = resp != null ? resp.get("result") : null;
        Object error = resp != null ? resp.get("error") : null;
        if (error != null) {
            log.warn("provider=odoo operation=reserveStock pickingId={} odooError={}", pickingId, error);
        } else {
            log.info("provider=odoo operation=reserveStock pickingId={} result={}", pickingId, result);
        }
    }

    private void setFullQuantityDoneOnMoveLines(Integer pickingId) {
        // action_set_quantities_to_reservation sets qty_done = reserved_qty on all move lines
        // Works on Odoo 16, 17, and 18 without field-name version differences
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(
                "stock.picking", "action_set_quantities_to_reservation", List.of(List.of(pickingId))));
        log.info("provider=odoo operation=setFullQuantityDone pickingId={} result={}",
                pickingId, resp != null ? resp.get("result") : "null");
    }


    private Map<String, Object> callValidatePicking(Integer pickingId) {
        return rpc.callRpc(rpc.buildArgs("stock.picking", "button_validate",
                List.of(List.of(pickingId)),
                Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));
    }

    private Map<String, Object> findSinglePicking(Integer erpOrderId) {
        // order by id asc to always get the oldest pending picking — deterministic on backorder chains
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("sale_id", "=", erpOrderId), List.of("state", "not in", List.of("done", "cancel")))), Map.of("fields", List.of("id", "state"), "limit", 1, "order", "id asc")));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    private Map<String, Object> findPickingById(Integer pickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read", List.of(List.of(List.of("id", "=", pickingId))), Map.of("fields", List.of("id", "state"), "limit", 1)));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    private Integer findBackorderPickingId(Integer originPickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read", List.of(List.of(List.of("backorder_id", "=", originPickingId))), Map.of("fields", List.of("id"), "limit", 1)));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? asInt(result.get(0).get("id")) : null;
    }

    private String readPickingState(Integer pickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "read", List.of(List.of(pickingId), List.of("state"))));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? (String) result.get(0).get("state") : null;
    }

    private String readSaleOrderState(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "read", List.of(List.of(erpOrderId), List.of("state"))));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? (String) result.get(0).get("state") : null;
    }

    private Map<Integer, Integer> resolvePartialQuantities(List<ErpPartialItemDTO> items) {
        Map<Integer, Integer> qtyMap = new HashMap<>();
        for (ErpPartialItemDTO item : items) {
            Integer pid = resolveProductId(item.getReferenceKey(), item.getItemName());
            if (pid != null) qtyMap.merge(pid, item.getQuantityDone(), Integer::sum);
        }
        return qtyMap;
    }

    /**
     * Resolves an Odoo product.product ID from a SKU (default_code).
     * Falls back to exact name match for service products that have no internal reference.
     */
    private Integer resolveProductId(String sku, String itemName) {
        // Primary: SKU / internal reference lookup
        if (sku != null && !sku.isBlank()) {
            try {
                Map<String, Object> res = rpc.callRpc(rpc.buildArgs("product.product", "search",
                        List.of(List.of(List.of("default_code", "=", sku))), Map.of("limit", 1)));
                List<Integer> ids = (List<Integer>) res.get("result");
                if (ids != null && !ids.isEmpty()) return ids.get(0);
            } catch (Exception e) {
                log.warn("provider=odoo operation=resolveProductId sku={} reason={}", sku, e.getMessage());
            }
        }
        // Fallback: exact product name — covers service products without a default_code
        if (itemName != null && !itemName.isBlank()) {
            try {
                Map<String, Object> res = rpc.callRpc(rpc.buildArgs("product.product", "search",
                        List.of(List.of(List.of("name", "=", itemName))), Map.of("limit", 1)));
                List<Integer> ids = (List<Integer>) res.get("result");
                if (ids != null && !ids.isEmpty()) {
                    log.info("provider=odoo operation=resolveProductId sku={} resolved_by_name={}", sku, itemName);
                    return ids.get(0);
                }
            } catch (Exception e) {
                log.warn("provider=odoo operation=resolveProductId itemName={} reason={}", itemName, e.getMessage());
            }
        }
        log.warn("provider=odoo operation=resolveProductId sku={} itemName={} reason=not_found action=skip", sku, itemName);
        return null;
    }

    /**
     * Writes qty_done to each stock.move.line for a partial delivery.
     * Matches move lines to items by fetching the product's default_code directly from Odoo —
     * avoids the SKU→product_id pre-resolution that fails when the SKU stored in our system
     * doesn't exactly match Odoo's default_code (e.g. bracket notation vs plain code).
     * Move lines for products not in the items list are explicitly set to 0.
     */
    /**
     * Returns total qty_done written (sum across all move lines).
     * Caller uses this to detect "all refused" and skip button_validate.
     */
    private int applyPartialQtyDoneToMoveLines(Integer pickingId, List<ErpPartialItemDTO> items) {
        // Three lookup strategies, tried in order for each move line:
        // 1. Numeric product_id: referenceKey is stored as the Odoo product DB id ("55", "27")
        // 2. SKU string: referenceKey is the product default_code ("FURN_5555", "E-COM11")
        // 3. Product name: itemName matches Odoo product name (service products without default_code)
        Map<Integer, Integer> pidToQty  = new HashMap<>();
        Map<String, Integer>  skuToQty  = new HashMap<>();
        Map<String, Integer>  nameToQty = new HashMap<>();
        for (ErpPartialItemDTO item : items) {
            String ref = item.getReferenceKey();
            if (ref != null && !ref.isBlank()) {
                try { pidToQty.put(Integer.parseInt(ref.trim()), item.getQuantityDone()); }
                catch (NumberFormatException ignored) { skuToQty.put(ref.trim(), item.getQuantityDone()); }
            }
            if (item.getItemName() != null && !item.getItemName().isBlank()) {
                nameToQty.put(item.getItemName().trim(), item.getQuantityDone());
            }
        }
        log.info("provider=odoo operation=applyPartialQty pickingId={} pidToQty={} skuToQty={}", pickingId, pidToQty, skuToQty);

        // Read move lines
        Map<String, Object> mlResp = rpc.callRpc(rpc.buildArgs("stock.move.line", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                Map.of("fields", List.of("id", "product_id"))));
        if (mlResp == null) {
            log.warn("provider=odoo operation=applyPartialQty pickingId={} reason=null_response", pickingId);
            return 0;
        }
        List<Map<String, Object>> lines = (List<Map<String, Object>>) mlResp.get("result");
        log.info("provider=odoo operation=applyPartialQty pickingId={} move_lines_found={}", pickingId,
                lines != null ? lines.size() : "null");
        if (lines == null || lines.isEmpty()) return 0;

        // Batch-fetch product details (default_code + name) for all products in this picking
        List<Integer> productIds = lines.stream()
                .map(l -> asRelId(l.get("product_id")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        Map<Integer, String> pidToSku  = new HashMap<>();
        Map<Integer, String> pidToName = new HashMap<>();
        if (!productIds.isEmpty()) {
            Map<String, Object> prodResp = rpc.callRpc(rpc.buildArgs("product.product", "search_read",
                    List.of(List.of(List.of("id", "in", productIds))),
                    Map.of("fields", List.of("id", "default_code", "name"))));
            List<Map<String, Object>> prods = prodResp != null ? (List<Map<String, Object>>) prodResp.get("result") : null;
            if (prods != null) {
                for (Map<String, Object> p : prods) {
                    Integer pid = asInt(p.get("id"));
                    if (pid == null) continue;
                    Object dc = p.get("default_code");
                    if (dc instanceof String s && !s.isBlank()) pidToSku.put(pid, s.trim());
                    Object nm = p.get("name");
                    if (nm instanceof String s && !s.isBlank()) pidToName.put(pid, s.trim());
                }
            }
        }
        log.info("provider=odoo operation=applyPartialQty pickingId={} productDetails={}", pickingId, pidToSku);

        // Write qty_done to each move line; tally total for caller's zero-check
        int totalWritten = 0;
        for (Map<String, Object> line : lines) {
            Integer pid   = asRelId(line.get("product_id"));
            String  sku   = pidToSku.get(pid);
            String  pName = pidToName.get(pid);

            Integer qty = null;
            if (pid  != null && pidToQty.containsKey(pid))   qty = pidToQty.get(pid);
            if (qty  == null && sku  != null)                 qty = skuToQty.get(sku);
            // Bracket-prefix fallback: skuToQty key may be "[FURN_6666] Product Name..." when SKU was
            // stored as null in DB and Flutter fell back to the full Odoo sale.order.line description.
            if (qty == null && sku != null) {
                final String bracketPrefix = "[" + sku + "]";
                qty = skuToQty.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(bracketPrefix))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(null);
            }
            if (qty  == null && pName != null)                qty = nameToQty.get(pName);
            if (qty  == null) qty = 0; // product not in delivery list → not delivered

            // Odoo 17+: "quantity" on stock.move.line is the done qty
            Map<String, Object> writeResp = rpc.callRpc(rpc.buildArgs("stock.move.line", "write",
                    List.of(List.of(line.get("id")), Map.of("quantity", qty))));
            log.info("provider=odoo operation=applyPartialQty pickingId={} lineId={} productId={} sku={} qty={} writeResult={}",
                    pickingId, line.get("id"), pid, sku, qty, writeResp != null ? writeResp.get("result") : "null");
            totalWritten += qty;
        }
        return totalWritten;
    }


    @SuppressWarnings("unchecked")
    private void syncSaleOrderLineDeliveredQuantities(Integer erpOrderId, List<ErpPartialItemDTO> items, boolean full) {
        try {
            if (full) {
                // For full delivery, Odoo automatically updates qty_delivered on sale.order.line
                // when the stock.picking is validated via button_validate. No explicit write needed.
                log.debug("syncSaleOrderLineDeliveredQuantities: full delivery for erpOrderId={} — Odoo handles qty_delivered automatically", erpOrderId);
                return;
            }

            // PARTIAL: resolve SKU → product_id → qty mapping
            if (items == null || items.isEmpty()) return;
            Map<Integer, Integer> productQtyDone = resolvePartialQuantities(items);
            if (productQtyDone.isEmpty()) {
                log.warn("syncSaleOrderLineDeliveredQuantities: no product IDs resolved for erpOrderId={}", erpOrderId);
                return;
            }

            // Fetch sale.order.line records for this order
            Map<String, Object> solResp = rpc.callRpc(rpc.buildArgs(
                    "sale.order.line", "search_read",
                    List.of(List.of(List.of("order_id", "=", erpOrderId))),
                    Map.of("fields", List.of("id", "product_id", "product_uom_qty", "qty_delivered"))));
            if (solResp == null) {
                log.warn("syncSaleOrderLineDeliveredQuantities: null response from Odoo for erpOrderId={}", erpOrderId);
                return;
            }

            List<Map<String, Object>> lines = (List<Map<String, Object>>) solResp.get("result");
            if (lines == null || lines.isEmpty()) return;

            for (Map<String, Object> line : lines) {
                Integer productId = asRelId(line.get("product_id"));
                if (productId == null) continue;
                Integer qtyDone = productQtyDone.get(productId);
                if (qtyDone == null) continue;

                // Cap qty_delivered at product_uom_qty to avoid over-delivery
                Object plannedRaw = line.get("product_uom_qty");
                int planned = plannedRaw instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
                int cappedQty = Math.min(qtyDone, planned);

                Map<String, Object> writeResp = rpc.callRpc(rpc.buildArgs(
                        "sale.order.line", "write",
                        List.of(List.of(line.get("id")), Map.of("qty_delivered", cappedQty))));
                if (writeResp != null && writeResp.containsKey("error")) {
                    log.warn("syncSaleOrderLineDeliveredQuantities: write error for line={} order={}: {}",
                            line.get("id"), erpOrderId, writeResp.get("error"));
                }
            }
            log.info("syncSaleOrderLineDeliveredQuantities: updated qty_delivered for {} lines, erpOrderId={}", lines.size(), erpOrderId);
        } catch (Exception e) {
            // Best-effort — do not propagate; the picking validation already succeeded
            log.warn("syncSaleOrderLineDeliveredQuantities failed for erpOrderId={}: {}", erpOrderId, e.getMessage(), e);
        }
    }

    public Integer resolveErpId(String erpOrderId) {
        if (erpOrderId == null || erpOrderId.isBlank()) return null;
        try {
            return Integer.parseInt(erpOrderId);
        } catch (Exception e) {
            Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "search",
                    List.of(List.of(List.of("name", "=", erpOrderId))), Map.of("limit", 1)));
            List<Integer> ids = (List<Integer>) response.get("result");
            return (ids != null && !ids.isEmpty()) ? ids.get(0) : null;
        }
    }

    private void addNoteToSaleOrder(Integer erpId, String note) {
        rpc.callRpc(rpc.buildArgs("sale.order", "message_post", List.of(List.of(erpId)), Map.of("body", note)));
    }

    /**
     * Adds the "Livraison Échouée" tag to the sale order so dispatchers can
     * filter failed deliveries directly from the Odoo sale order list.
     * Tag ID is resolved once and cached for the lifetime of this bean.
     */
    private void tagOrderAsDeliveryFailed(Integer erpId) {
        try {
            Integer tagId = resolveOrCreateDeliveryFailedTag();
            if (tagId == null) {
                log.warn("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} reason=tag_id_null", erpId);
                return;
            }
            // ORM command (4, id) = link existing record without replacing others
            rpc.callRpc(rpc.buildArgs("sale.order", "write",
                    List.of(List.of(erpId), Map.of("tag_ids", List.of(List.of(4, tagId))))));
            log.info("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} tagId={}", erpId, tagId);
        } catch (Exception e) {
            // Non-critical — don't fail the whole sync if tagging fails
            log.warn("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} reason={}", erpId, e.getMessage());
        }
    }

    /** Lazily resolves the "Livraison Échouée" tag ID, creating it if it doesn't exist. */
    private Integer resolveOrCreateDeliveryFailedTag() {
        if (deliveryFailedTagId != null) return deliveryFailedTagId;

        synchronized (this) {
            if (deliveryFailedTagId != null) return deliveryFailedTagId;

            final String tagName = "Livraison Échouée";

            // Try to find existing tag
            Map<String, Object> searchResp = rpc.callRpc(rpc.buildArgs(
                    "crm.tag", "search_read",
                    List.of(List.of(List.of("name", "=", tagName))),
                    Map.of("fields", List.of("id"), "limit", 1)));
            List<?> found = searchResp != null ? (List<?>) searchResp.get("result") : null;
            if (found != null && !found.isEmpty()) {
                deliveryFailedTagId = asInt(((Map<?, ?>) found.get(0)).get("id"));
                log.info("provider=odoo operation=resolveOrCreateDeliveryFailedTag action=found tagId={}", deliveryFailedTagId);
                return deliveryFailedTagId;
            }

            // Create if not found
            Map<String, Object> createResp = rpc.callRpc(rpc.buildArgs(
                    "crm.tag", "create", List.of(Map.of("name", tagName))));
            Object created = createResp != null ? createResp.get("result") : null;
            if (created instanceof Number n) {
                deliveryFailedTagId = n.intValue();
                log.info("provider=odoo operation=resolveOrCreateDeliveryFailedTag action=created tagId={}", deliveryFailedTagId);
                return deliveryFailedTagId;
            }

            log.warn("provider=odoo operation=resolveOrCreateDeliveryFailedTag reason=create_returned_null response={}", createResp);
            return null;
        }
    }

    /**
     * Builds an HTML chatter note summarising the partial delivery outcome per item.
     * Includes: refused/damaged items, partial-qty delivered items, and any driver comments.
     * Returns null when all items are fully DELIVERED with no comments.
     */
    private String buildPartialDeliveryNote(List<com.asm.erpadapter.dto.ErpPartialItemDTO> items) {
        if (items == null || items.isEmpty()) return null;

        // Separate items into refused/damaged vs delivered (full or partial)
        List<com.asm.erpadapter.dto.ErpPartialItemDTO> refused = items.stream()
                .filter(i -> i != null && ("REFUSED".equalsIgnoreCase(i.getOutcome()) || "DAMAGED".equalsIgnoreCase(i.getOutcome())))
                .collect(java.util.stream.Collectors.toList());

        // DELIVERED items that have a driver comment (typically partial-qty deliveries)
        List<com.asm.erpadapter.dto.ErpPartialItemDTO> deliveredWithComment = items.stream()
                .filter(i -> i != null
                        && "DELIVERED".equalsIgnoreCase(i.getOutcome())
                        && i.getComment() != null && !i.getComment().isBlank())
                .collect(java.util.stream.Collectors.toList());

        if (refused.isEmpty() && deliveredWithComment.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("<b>ASM Track — Livraison partielle</b><br/>");

        if (!refused.isEmpty()) {
            sb.append("<b>Articles non livrés :</b><ul>");
            for (com.asm.erpadapter.dto.ErpPartialItemDTO item : refused) {
                String label = (item.getItemName() != null && !item.getItemName().isBlank())
                        ? item.getItemName() : item.getReferenceKey();
                sb.append("<li><b>").append(label).append("</b>");
                if ("DAMAGED".equalsIgnoreCase(item.getOutcome())) {
                    sb.append(" — Endommagé");
                } else {
                    sb.append(" — Refusé");
                }
                if (item.getReason() != null && !item.getReason().isBlank()) {
                    sb.append(" (").append(humanizeReason(item.getReason())).append(")");
                }
                if (item.getComment() != null && !item.getComment().isBlank()) {
                    sb.append("<br/><i>").append(item.getComment()).append("</i>");
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        if (!deliveredWithComment.isEmpty()) {
            sb.append("<b>Notes chauffeur :</b><ul>");
            for (com.asm.erpadapter.dto.ErpPartialItemDTO item : deliveredWithComment) {
                String label = (item.getItemName() != null && !item.getItemName().isBlank())
                        ? item.getItemName() : item.getReferenceKey();
                sb.append("<li><b>").append(label).append("</b>");
                sb.append(" — Livré : <i>").append(item.getComment()).append("</i></li>");
            }
            sb.append("</ul>");
        }

        return sb.toString();
    }

    private Integer asInt(Object o) { return o instanceof Number n ? n.intValue() : null; }
    private Integer asRelId(Object o) { if (o instanceof List<?> l && !l.isEmpty()) return asInt(l.get(0)); return null; }
}
