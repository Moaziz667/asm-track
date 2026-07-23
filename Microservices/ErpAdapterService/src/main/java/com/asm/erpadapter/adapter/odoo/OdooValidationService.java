package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.adapter.odoo.workflow.DeliveryValidationHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final DeliveryValidationHandler wizardHandler;
    private final OdooSaleOrderService saleOrderService;
    private final CapabilityResolver capabilityResolver;

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
        String method = capabilityResolver.resolve(CanonicalCapability.RESERVE_STOCK);
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", method,
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
        String method = capabilityResolver.resolve(CanonicalCapability.FORCE_AVAILABILITY);
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", method, List.of(List.of(pickingId))));
        Object error = resp != null ? resp.get("error") : null;
        if (error != null) {
            log.warn("provider=odoo operation=forceAvailability pickingId={} odooError={}", pickingId, error);
        }
    }

    // ── Set full quantities ───────────────────────────────────────────────────

    /**
     * Set qty_done = reserved_qty on all move lines ({@code action_set_quantities_to_reservation}).
     * Works on Odoo 16, 17, and 18 without field-name version differences.
     */
    public void setFullQuantityDoneOnMoveLines(Integer pickingId) {
        String method = capabilityResolver.resolve(CanonicalCapability.SET_FULL_QUANTITY);
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(
                "stock.picking", method, List.of(List.of(pickingId))));
        Object error = resp != null ? resp.get("error") : null;
        if (error != null) {
            log.warn("provider=odoo operation=setFullQuantityDone pickingId={} odooError={}", pickingId, error);
        } else {
            log.info("provider=odoo operation=setFullQuantityDone pickingId={} result={}",
                    pickingId, resp != null ? resp.get("result") : "null");
        }
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

    public String readSaleOrderState(Integer erpOrderId) {
        return saleOrderService.readSaleOrderState(erpOrderId);
    }

    // ── Private implementation ────────────────────────────────────────────────

    /**
     * Core validation flow: reserve stock → set quantities → button_validate → handle wizard.
     * Handles the three possible wizard outcomes across Odoo 16→19.
     */
    private boolean doValidateTransfer(Integer erpOrderId, Integer pickingId) {
        reserveStock(pickingId);

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
        String validateMethod = capabilityResolver.resolve(CanonicalCapability.DELIVERY_VALIDATE);
        Map<String, Object> validateResp = rpc.callRpc(
                rpc.buildArgs("stock.picking", validateMethod,
                        List.of(List.of(pickingId)),
                        Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));

        Object rawResult = validateResp != null ? validateResp.get("result") : null;
        if (rawResult instanceof Map<?, ?> res) {
            wizardHandler.handleWizard(pickingId, res);
        }

        String finalState = pickingService.readPickingState(pickingId);
        if (!"done".equalsIgnoreCase(finalState)) {
            log.warn("ERP sync failed — provider=odoo operation=doValidateTransfer erpOrderId={} pickingId={} finalState={} reason=picking_not_done retryable=true",
                    erpOrderId, pickingId, finalState);
            return false;
        }
        return true;
    }

    // ── Wizard handling (delegation) ───────────────────────────────────────────

    /**
     * Handle the wizard returned by {@code button_validate}. Delegates to
     * {@link DeliveryValidationHandler} for version-specific wizard logic.
     */
    public void handleWizard(Integer pickingId, Map<?, ?> res) {
        wizardHandler.handleWizard(pickingId, res);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Raw button_validate call — used by partial delivery workflow that handles
     * the wizard response itself.
     */
    public Map<String, Object> callValidatePicking(Integer pickingId) {
        String validateMethod = capabilityResolver.resolve(CanonicalCapability.DELIVERY_VALIDATE);
        return rpc.callRpc(rpc.buildArgs("stock.picking", validateMethod,
                List.of(List.of(pickingId)),
                Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));
    }
}
