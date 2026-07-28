package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.adapter.odoo.workflow.ReturnHandler;
import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.dto.ErpPodDTO;
import com.asm.erpadapter.dto.ErpReturnItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Odoo implementation of {@link ErpSyncPort}.
 *
 * <p>Thin facade that delegates all reusable Odoo operations to dedicated services:
 * <ul>
 *   <li>{@link OdooPickingService} — picking lookups/mutations</li>
 *   <li>{@link OdooValidationService} — validation + wizard handling</li>
 *   <li>{@link OdooSaleOrderService} — SO operations, notes, tags</li>
 *   <li>{@link OdooProductService} — product resolution + qty writes</li>
 *   <li>{@link OdooPodService} — POD attachments + photo fetch</li>
 * </ul>
 *
 * <p>Refactored for 'Exactly-Once' delivery using IdempotencyService.
 */
@Component("odoo")
@RequiredArgsConstructor
@Slf4j
public class OdooSyncAdapter implements ErpSyncPort {

    private final OdooJsonRpcClient rpc;
    private final IdempotencyService idempotency;
    private final OdooPickingService pickingService;
    private final OdooValidationService validationService;
    private final OdooSaleOrderService saleOrderService;
    private final OdooProductService productService;
    private final OdooPodService podService;
    private final ReturnHandler returnHandler;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Per-picking in-flight key so two pickings of the same sale order don't block each other. */
    private static String inFlightKey(String erpOrderId, String pickingRef) {
        return (pickingRef != null && !pickingRef.isBlank()) ? erpOrderId + "|" + pickingRef : erpOrderId;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API — ErpSyncPort (facade + idempotency + in-flight guard)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean syncOrderCancellation(String erpOrderId, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncOrderCancellation(erpOrderId, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncFullDelivery(erpOrderId, backorderPickingId, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, ErpPartialDeliveryResultDTO.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return ErpPartialDeliveryResultDTO.builder().success(false).build();
            try { return doSyncPartialDelivery(erpOrderId, items, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncFailure(erpOrderId, failureCode, comment); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncProofOfDelivery(String erpOrderId, ErpPodDTO pod, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try {
                // Resolve through the PICKING when the reference is a BL name — which it always is
                // for BL-imported orders, and a sale.order is never named "WH/OUT/xxxxx". When the
                // picking carries no sale order at all, attach the proof to the picking rather than
                // failing the whole sync.
                Integer erpId = saleOrderService.resolveSaleOrderId(erpOrderId, pickingRef);
                if (erpId != null) {
                    return podService.syncProofOfDelivery(erpId, pod);
                }
                Map<String, Object> picking = findPickingForRef(erpOrderId, pickingRef);
                if (picking == null) {
                    log.warn("ERP sync failed — provider=odoo operation=syncPod erpOrderId={} pickingRef={} "
                            + "reason=no_sale_order_and_no_picking retryable=false", erpOrderId, pickingRef);
                    return false;
                }
                return podService.syncProofOfDelivery("stock.picking",
                        ((Number) picking.get("id")).intValue(), pod);
            }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncReturn(String erpOrderId, List<ErpReturnItemDTO> items, String reason, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncReturn(erpOrderId, items, reason, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncReschedule(String erpOrderId, String scheduledAt, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncReschedule(erpOrderId, scheduledAt); }
            finally { inFlight.remove(key); }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Invoice — admin-triggered, synchronous (not part of delivery outbox)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public String createInvoice(String erpOrderId, String pickingRef) {
        try {
            Integer soId = saleOrderService.resolveErpId(erpOrderId);
            if (soId == null) {
                log.warn("provider=odoo operation=createInvoice erpOrderId={} reason=so_not_found", erpOrderId);
                return null;
            }
            Map<String, Object> ctx = Map.of("active_model", "sale.order", "active_ids", List.of(soId), "active_id", soId);
            Map<String, Object> wizResp = rpc.callRpc(rpc.buildArgs("sale.advance.payment.inv", "create",
                    List.of(Map.of("advance_payment_method", "delivered")), Map.of("context", ctx)));
            Integer wizId = asInt(wizResp != null ? wizResp.get("result") : null);
            if (wizId == null) {
                log.warn("provider=odoo operation=createInvoice erpOrderId={} reason=wizard_create_failed", erpOrderId);
                return null;
            }
            rpc.callRpc(rpc.buildArgs("sale.advance.payment.inv", "create_invoices",
                    List.of(List.of(wizId)), Map.of("context", ctx)));

            Map<String, Object> soRead = rpc.callRpc(rpc.buildArgs("sale.order", "read",
                    List.of(List.of(soId), List.of("invoice_ids"))));
            List<Integer> invoiceIds = extractInvoiceIds(soRead);
            if (invoiceIds.isEmpty()) {
                log.warn("provider=odoo operation=createInvoice erpOrderId={} reason=no_invoice_created", erpOrderId);
                return null;
            }
            rpc.callRpc(rpc.buildArgs("account.move", "action_post", List.of(invoiceIds)));
            Map<String, Object> invRead = rpc.callRpc(rpc.buildArgs("account.move", "read",
                    List.of(invoiceIds, List.of("name"))));
            String name = extractFirstName(invRead);
            log.info("provider=odoo operation=createInvoice erpOrderId={} soId={} invoice={}", erpOrderId, soId, name);
            return name;
        } catch (Exception e) {
            log.warn("provider=odoo operation=createInvoice erpOrderId={} failed: {}", erpOrderId, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Workflow Implementations (private)
    // ══════════════════════════════════════════════════════════════════════════

    private boolean doSyncOrderCancellation(String erpOrderId, String pickingRef) {
        if (pickingRef != null && !pickingRef.isBlank()) {
            Map<String, Object> picking = pickingService.findPickingByName(pickingRef);
            if (picking == null) {
                log.warn("ERP sync failed — provider=odoo operation=syncOrderCancellation pickingRef={} reason=picking_not_found retryable=false", pickingRef);
                return false;
            }
            return pickingService.cancelPicking(((Number) picking.get("id")).intValue());
        }

        Integer erpId = saleOrderService.resolveSaleOrderId(erpOrderId, pickingRef);
        if (erpId == null) log.warn("ERP sync failed — provider=odoo operation=syncOrderCancellation erpOrderId={} reason=sale_order_not_found retryable=false", erpOrderId);
        if (erpId == null) return false;

        try {
            return saleOrderService.cancelSaleOrder(erpId);
        } catch (com.asm.erpadapter.adapter.odoo.workflow.SaleOrderNotFoundException e) {
            log.warn("ERP sync failed — provider=odoo operation=syncOrderCancellation erpOrderId={} erpId={} reason=order_not_found retryable=false",
                    erpOrderId, erpId);
            return false;
        }
    }

    private boolean doSyncFullDelivery(String erpOrderId, Integer backorderPickingId, String pickingRef) {
        if (backorderPickingId != null) {
            try {
                boolean transferOk = validationService.validateTransferByPickingId(backorderPickingId);
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

        if (pickingRef != null && !pickingRef.isBlank()) {
            Map<String, Object> picking = pickingService.findPickingByName(pickingRef);
            if (picking == null) {
                log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery pickingRef={} reason=picking_not_found retryable=false", pickingRef);
                return false;
            }
            try {
                return validationService.validateTransferByPickingId(((Number) picking.get("id")).intValue());
            } catch (Exception e) {
                log.error("ERP sync exception — provider=odoo operation=syncFullDelivery pickingRef={} errorClass={} reason={} retryable=true",
                        pickingRef, e.getClass().getSimpleName(), e.getMessage(), e);
                return false;
            }
        }

        Integer erpId = saleOrderService.resolveSaleOrderId(erpOrderId, pickingRef);
        if (erpId == null) log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery erpOrderId={} reason=sale_order_not_found retryable=false", erpOrderId);
        if (erpId == null) return false;

        try {
            boolean transferOk = validationService.validateTransfer(erpId, null);
            if (!transferOk) {
                log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery erpOrderId={} erpId={} reason=transfer_validation_failed retryable=true",
                        erpOrderId, erpId);
                return false;
            }
            productService.syncSaleOrderLineDeliveredQuantities(erpId, null, true);
            return true;
        } catch (Exception e) {
            log.error("ERP sync exception — provider=odoo operation=syncFullDelivery erpOrderId={} erpId={} errorClass={} reason={} retryable=true",
                    erpOrderId, erpId, e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Locate the picking a sync refers to, tolerating the fact that the ERP reference ASM stores IS
     * the picking name for BL-imported orders.
     */
    private Map<String, Object> findPickingForRef(String erpOrderId, String pickingRef) {
        for (String ref : new String[]{pickingRef, erpOrderId}) {
            if (ref == null || ref.isBlank()) continue;
            Map<String, Object> p = pickingService.findPickingByName(ref);
            // An id-less map is "not found", not a picking — guards against an empty search_read
            // payload being mistaken for a hit (and NPE-ing on the id read further down).
            if (p != null && p.get("id") != null) return p;
        }
        return null;
    }

    private ErpPartialDeliveryResultDTO doSyncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items, String pickingRef) {
        // May be null: a BL can be a standalone picking with no sale order. That must NOT abort the
        // sync — validating the picking is the point; the sale-order steps (delivered quantities,
        // chatter note) are enrichment and are skipped when there is no order.
        Integer erpId = saleOrderService.resolveSaleOrderId(erpOrderId, pickingRef);

        try {
            if (erpId != null) validationService.confirmOrderIfNeeded(erpId);
            Map<String, Object> picking = findPickingForRef(erpOrderId, pickingRef);
            if (picking == null && erpId != null) picking = pickingService.findSinglePicking(erpId);
            if (picking == null) {
                log.warn("ERP sync failed — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} pickingRef={} reason=no_picking_found retryable=true",
                        erpOrderId, erpId, pickingRef);
                return ErpPartialDeliveryResultDTO.builder().success(false).build();
            }

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            if ("done".equals(state)) {
                if (erpId != null) productService.syncSaleOrderLineDeliveredQuantities(erpId, items, false);
                Integer bo = pickingService.findBackorderPickingId(pickingId);
                return ErpPartialDeliveryResultDTO.builder()
                        .success(true).pickingId(pickingId).backorderPickingId(bo)
                        .backorderBlNumber(pickingService.readPickingName(bo)).build();
            }

            validationService.reserveStock(pickingId);
            String stateAfterReserve = pickingService.readPickingState(pickingId);
            if (!"assigned".equalsIgnoreCase(stateAfterReserve)) {
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} state={} action=force_availability",
                        pickingId, stateAfterReserve);
                validationService.forceAvailability(pickingId);
            }

            int totalQtyDone = productService.applyPartialQtyDoneToMoveLines(pickingId, items);

            if (totalQtyDone == 0) {
                String allRefusedNote = buildPartialDeliveryNote(items);
                if (allRefusedNote != null && erpId != null) saleOrderService.addNoteToSaleOrder(erpId, allRefusedNote);
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} action=skip_validation reason=all_qty_zero", pickingId);
                return ErpPartialDeliveryResultDTO.builder().success(true).pickingId(pickingId).backorderPickingId(null).build();
            }

            Map<String, Object> validateResponse = validationService.callValidatePicking(pickingId);
            if (validateResponse == null || validateResponse.containsKey("error")) {
                Object odooError = validateResponse != null ? validateResponse.get("error") : "null_response";
                log.warn("ERP sync failed — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} pickingId={} odooError={} retryable=true",
                        erpOrderId, erpId, pickingId, odooError);
                return ErpPartialDeliveryResultDTO.builder().success(false).build();
            }

            if (validateResponse.get("result") instanceof Map<?, ?> res) {
                String resModel = (String) res.get("res_model");
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} wizard={} action=confirming", pickingId, resModel);
                if ("stock.immediate.transfer".equals(resModel)) {
                    // NB: Immediate transfer uses _action_done directly (not the standard wizard process).
                    // This is a deliberate bypass — the IMMEDIATE_TRANSFER capability's `process` method
                    // doesn't handle partial-qty scenarios where some lines are skipped.
                    // _action_done is a stable Odoo internal method used by the stock module itself.
                    log.info("provider=odoo operation=syncPartialDelivery pickingId={} wizard=immediate_transfer action=_action_done_direct", pickingId);
                    rpc.callRpc(rpc.buildArgs("stock.picking", "_action_done", List.of(List.of(pickingId))));
                } else {
                    // Backorder and SMS wizards — delegate to handler
                    validationService.handleWizard(pickingId, res);
                }
            }

            // Sale-order enrichment only when the picking actually belongs to an order.
            if (erpId != null) {
                productService.syncSaleOrderLineDeliveredQuantities(erpId, items, false);
                String partialNote = buildPartialDeliveryNote(items);
                if (partialNote != null) {
                    saleOrderService.addNoteToSaleOrder(erpId, partialNote);
                }
            }

            Integer backorderPickingId = pickingService.findBackorderPickingId(pickingId);
            log.info("provider=odoo operation=syncPartialDelivery pickingId={} backorderPickingId={}", pickingId, backorderPickingId);
            return ErpPartialDeliveryResultDTO.builder()
                    .success(true).pickingId(pickingId).backorderPickingId(backorderPickingId)
                    .backorderBlNumber(pickingService.readPickingName(backorderPickingId)).build();
        } catch (Exception e) {
            log.error("ERP sync exception — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} errorClass={} reason={} retryable=true",
                    erpOrderId, erpId, e.getClass().getSimpleName(), e.getMessage(), e);
            return ErpPartialDeliveryResultDTO.builder().success(false).build();
        }
    }

    private boolean doSyncReschedule(String erpOrderId, String scheduledAt) {
        Integer erpId = saleOrderService.resolveSaleOrderId(erpOrderId, null);
        if (erpId == null) log.warn("ERP sync failed — provider=odoo operation=syncReschedule erpOrderId={} reason=sale_order_not_found retryable=false", erpOrderId);
        if (erpId == null) return false;
        String odooDt = toOdooDateTime(scheduledAt);
        if (odooDt != null) {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "write",
                    List.of(List.of(erpId), Map.of("commitment_date", odooDt))));
            if (resp != null && resp.containsKey("error")) {
                log.warn("ERP sync failed — provider=odoo operation=syncReschedule erpId={} odooError={} retryable=true",
                        erpId, resp.get("error"));
                return false;
            }
        }
        saleOrderService.addNoteToSaleOrder(erpId, "<b>ASM Track — Replanification</b><br/>Nouvelle date de livraison : "
                + (scheduledAt != null ? scheduledAt : "—"));
        log.info("provider=odoo operation=syncReschedule erpId={} commitment_date={}", erpId, odooDt);
        return true;
    }

    private String toOdooDateTime(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim().replace('T', ' ');
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        if (s.length() == 16) s = s + ":00";
        return s;
    }

    private boolean doSyncFailure(String erpOrderId, String failureCode, String comment) {
        Integer erpId = saleOrderService.resolveSaleOrderId(erpOrderId, null);
        if (erpId == null) log.warn("ERP sync failed — provider=odoo operation=syncFailure erpOrderId={} reason=sale_order_not_found retryable=false", erpOrderId);
        if (erpId == null) return false;
        saleOrderService.addNoteToSaleOrder(erpId, buildFailureNote(failureCode, comment));
        saleOrderService.tagOrderAsDeliveryFailed(erpId);
        return true;
    }

    private boolean doSyncReturn(String erpOrderId, List<ErpReturnItemDTO> items, String reason, String pickingRef) {
        Integer erpId = saleOrderService.resolveSaleOrderId(erpOrderId, pickingRef);
        if (erpId == null) log.warn("ERP sync failed — provider=odoo operation=syncReturn erpOrderId={} reason=sale_order_not_found retryable=false", erpOrderId);
        if (erpId == null) return false;
        saleOrderService.addNoteToSaleOrder(erpId, buildReturnNote(items, reason));
        return createReturnPicking(erpId, items, pickingRef);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Return / RMA orchestration (multi-service, stays in adapter)
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private boolean createReturnPicking(Integer erpId, List<ErpReturnItemDTO> items, String pickingRef) {
        try {
        Map<String, Object> picking = findReturnSourcePicking(erpId, pickingRef);
        if (picking == null) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} pickingRef={} reason=no_done_picking retryable=true", erpId, pickingRef);
            return false;
        }
        Integer pickingId = ((Number) picking.get("id")).intValue();

        Integer existingReturnId = findExistingReturnPicking(pickingId);
        if (existingReturnId != null) {
            boolean alreadyDone = "done".equalsIgnoreCase(pickingService.readPickingState(existingReturnId));
            log.info("provider=odoo operation=syncReturn erpId={} sourcePickingId={} existingReturnPickingId={} alreadyDone={} action=resume_existing",
                    erpId, pickingId, existingReturnId, alreadyDone);
            boolean done = alreadyDone || validationService.validateTransferByPickingId(existingReturnId);
            if (!done) {
                log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} existingReturnPickingId={} reason=existing_return_not_done retryable=true", erpId, existingReturnId);
                return false;
            }
            if (!alreadyDone) scrapDamagedReturnedItems(erpId, items);
            return true;
        }

        Map<String, Object> ctx = Map.of("active_id", pickingId, "active_model", "stock.picking", "active_ids", List.of(pickingId));
        Map<String, Object> wizardResp = rpc.callRpc(rpc.buildArgs("stock.return.picking", "create",
                List.of(Map.of("picking_id", pickingId)), Map.of("context", ctx)));
        Integer wizardId = asInt(wizardResp != null ? wizardResp.get("result") : null);
        if (wizardId == null) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} pickingId={} reason=wizard_unavailable retryable=true", erpId, pickingId);
            return false;
        }

        // Odoo only prefills the wizard's lines for a UI-driven create; over RPC, 16 leaves it empty.
        // Without lines, create_returns fails with "specify at least one non-zero quantity".
        if (productService.ensureReturnWizardLines(wizardId, pickingId) == 0) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} wizardId={} pickingId={} reason=return_wizard_has_no_lines retryable=true",
                    erpId, wizardId, pickingId);
            return false;
        }
        productService.applyReturnQuantities(wizardId, items);

        Map<String, Object> returnResp = returnHandler.callCreateReturns(wizardId, ctx);
        Integer returnPickingId = extractReturnPickingId(returnResp);
        if (returnPickingId == null) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} wizardId={} reason=return_picking_id_unresolved retryable=true result={}",
                    erpId, wizardId, returnResp != null ? returnResp.get("result") : null);
            return false;
        }
        log.info("provider=odoo operation=syncReturn erpId={} sourcePickingId={} returnPickingId={} action=return_created", erpId, pickingId, returnPickingId);

        boolean done = validationService.validateTransferByPickingId(returnPickingId);
        if (!done) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} returnPickingId={} reason=return_picking_not_done retryable=true", erpId, returnPickingId);
            return false;
        }

        scrapDamagedReturnedItems(erpId, items);
        return true;
        } catch (Exception e) {
            log.error("ERP sync exception — provider=odoo operation=syncReturn erpId={} errorClass={} reason={} retryable=true",
                    erpId, e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findReturnSourcePicking(Integer erpOrderId, String pickingRef) {
        if (pickingRef != null && !pickingRef.isBlank()) {
            Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                    List.of(List.of(
                            List.of("sale_id", "=", erpOrderId),
                            List.of("state", "=", "done"),
                            List.of("name", "=", pickingRef.trim()))),
                    Map.of("fields", List.of("id", "state", "name"), "limit", 1)));
            List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
            if (result != null && !result.isEmpty()) {
                log.info("provider=odoo operation=findReturnSourcePicking erpOrderId={} pickingRef={} action=matched_by_name", erpOrderId, pickingRef);
                return result.get(0);
            }
            log.info("provider=odoo operation=findReturnSourcePicking erpOrderId={} pickingRef={} action=name_no_match fallback=most_recent_done", erpOrderId, pickingRef);
        }
        return pickingService.findDonePicking(erpOrderId);
    }

    @SuppressWarnings("unchecked")
    private Integer extractReturnPickingId(Map<String, Object> returnResp) {
        Object result = returnResp != null ? returnResp.get("result") : null;
        if (!(result instanceof Map<?, ?> action)) {
            return asInt(result);
        }
        Integer resId = asInt(action.get("res_id"));
        if (resId != null && resId > 0) return resId;
        Object domain = action.get("domain");
        if (domain instanceof List<?> clauses) {
            for (Object clause : clauses) {
                if (clause instanceof List<?> triplet && triplet.size() == 3 && "id".equals(triplet.get(0))) {
                    Object val = triplet.get(2);
                    if (val instanceof Number n) return n.intValue();
                    if (val instanceof List<?> ids && !ids.isEmpty() && ids.get(0) instanceof Number n) return n.intValue();
                }
            }
        }
        log.warn("extractReturnPickingId could not extract picking id from returnResp={}", returnResp);
        return null;
    }

    @SuppressWarnings("unchecked")
    private Integer findExistingReturnPicking(Integer sourcePickingId) {
        Map<String, Object> srcMovesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", sourcePickingId))),
                Map.of("fields", List.of("id"))));
        List<Map<String, Object>> srcMoves = srcMovesResp != null ? (List<Map<String, Object>>) srcMovesResp.get("result") : null;
        if (srcMoves == null || srcMoves.isEmpty()) return null;
        List<Integer> srcMoveIds = srcMoves.stream().map(m -> asInt(m.get("id")))
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (srcMoveIds.isEmpty()) return null;

        Map<String, Object> retMovesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("origin_returned_move_id", "in", srcMoveIds))),
                Map.of("fields", List.of("picking_id"))));
        List<Map<String, Object>> retMoves = retMovesResp != null ? (List<Map<String, Object>>) retMovesResp.get("result") : null;
        if (retMoves == null || retMoves.isEmpty()) return null;
        List<Integer> retPickingIds = retMoves.stream().map(m -> asRelId(m.get("picking_id")))
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (retPickingIds.isEmpty()) return null;

        Map<String, Object> pickResp = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("id", "in", retPickingIds))),
                Map.of("fields", List.of("id", "state"))));
        List<Map<String, Object>> picks = pickResp != null ? (List<Map<String, Object>>) pickResp.get("result") : null;
        if (picks == null || picks.isEmpty()) return null;
        Integer firstLive = null;
        for (Map<String, Object> p : picks) {
            String state = (String) p.get("state");
            if ("cancel".equalsIgnoreCase(state)) continue;
            Integer id = asInt(p.get("id"));
            if ("done".equalsIgnoreCase(state)) return id;
            if (firstLive == null) firstLive = id;
        }
        return firstLive;
    }

    private void scrapDamagedReturnedItems(Integer erpId, List<ErpReturnItemDTO> items) {
        if (items == null) return;
        for (ErpReturnItemDTO it : items) {
            if (it == null || !"DAMAGED".equalsIgnoreCase(it.getCondition())) continue;
            if (it.getQuantity() == null || it.getQuantity() <= 0) continue;
            Integer productId = productService.resolveProductId(it.getSku(), it.getName());
            if (productId == null) {
                log.info("provider=odoo operation=scrapDamaged erpId={} sku={} action=skip reason=product_not_found", erpId, it.getSku());
                continue;
            }
            try {
                Map<String, Object> scrapResp = rpc.callRpc(rpc.buildArgs("stock.scrap", "create",
                        List.of(Map.of("product_id", productId, "scrap_qty", it.getQuantity()))));
                Integer scrapId = asInt(scrapResp != null ? scrapResp.get("result") : null);
                if (scrapId != null) {
                    rpc.callRpc(rpc.buildArgs("stock.scrap", "action_validate", List.of(List.of(scrapId))));
                    log.info("provider=odoo operation=scrapDamaged erpId={} sku={} qty={} scrapId={} action=scrapped",
                            erpId, it.getSku(), it.getQuantity(), scrapId);
                }
            } catch (Exception e) {
                log.warn("provider=odoo operation=scrapDamaged erpId={} sku={} action=skip reason={}", erpId, it.getSku(), e.getMessage());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Note Builders (workflow-specific, not reusable)
    // ══════════════════════════════════════════════════════════════════════════

    private String buildFailureNote(String failureCode, String comment) {
        StringBuilder sb = new StringBuilder("<b>ASM Track — Livraison échouée</b><br/>");
        sb.append("<b>Code :</b> ").append(failureCode != null ? failureCode : "UNKNOWN").append("<br/>");
        if (comment != null && !comment.isBlank()) {
            sb.append("<b>Commentaire :</b> ").append(comment);
        }
        return sb.toString();
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

    private String buildReturnNote(List<ErpReturnItemDTO> items, String reason) {
        StringBuilder sb = new StringBuilder("<b>ASM Track — Retour client (RMA)</b><br/>");
        if (reason != null && !reason.isBlank()) sb.append("<b>Motif :</b> ").append(reason).append("<br/>");
        if (items != null && !items.isEmpty()) {
            sb.append("<b>Articles retournés :</b><ul>");
            for (ErpReturnItemDTO it : items) {
                sb.append("<li>")
                  .append(it.getQuantity() != null ? it.getQuantity() : "?").append("× ")
                  .append(it.getName() != null ? it.getName() : (it.getSku() != null ? it.getSku() : "Article"))
                  .append(it.getCondition() != null ? " (" + it.getCondition() + ")" : "")
                  .append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.toString();
    }

    private String buildPartialDeliveryNote(List<ErpPartialItemDTO> items) {
        if (items == null || items.isEmpty()) return null;
        StringBuilder undelivered = new StringBuilder();
        StringBuilder driverNotes = new StringBuilder();

        for (ErpPartialItemDTO item : items) {
            if (item == null) continue;
            String label = (item.getItemName() != null && !item.getItemName().isBlank())
                    ? item.getItemName() : item.getReferenceKey();

            List<com.asm.erpadapter.dto.ErpItemSegmentDTO> nonDelivered = new ArrayList<>();
            if (item.hasSegments()) {
                for (com.asm.erpadapter.dto.ErpItemSegmentDTO seg : item.getSegments()) {
                    if (seg == null) continue;
                    if (!"DELIVERED".equalsIgnoreCase(seg.getDisposition())) nonDelivered.add(seg);
                }
            }

            if (!nonDelivered.isEmpty()) {
                undelivered.append("<li><b>").append(label).append("</b>");
                for (com.asm.erpadapter.dto.ErpItemSegmentDTO seg : nonDelivered) {
                    undelivered.append("<br/>&bull; ").append(dispositionLabel(seg.getDisposition()));
                    int q = seg.getQuantity() != null ? seg.getQuantity() : 0;
                    if (q > 0) undelivered.append(" ×").append(q);
                    String reasonText = (seg.getReasonLabel() != null && !seg.getReasonLabel().isBlank())
                            ? seg.getReasonLabel() : humanizeReason(seg.getReasonCode());
                    if (reasonText != null && !reasonText.isBlank()) {
                        undelivered.append(" (").append(reasonText).append(")");
                    }
                    if (seg.getComment() != null && !seg.getComment().isBlank()) {
                        undelivered.append(" — <i>").append(seg.getComment()).append("</i>");
                    }
                }
                undelivered.append("</li>");
            } else if ("REFUSED".equalsIgnoreCase(item.getOutcome()) || "DAMAGED".equalsIgnoreCase(item.getOutcome())) {
                undelivered.append("<li><b>").append(label).append("</b> — ")
                        .append("DAMAGED".equalsIgnoreCase(item.getOutcome()) ? "Endommagé" : "Refusé");
                String reasonText = (item.getReasonLabel() != null && !item.getReasonLabel().isBlank())
                        ? item.getReasonLabel() : humanizeReason(item.getReason());
                if (reasonText != null && !reasonText.isBlank()) {
                    undelivered.append(" (").append(reasonText).append(")");
                }
                if (item.getComment() != null && !item.getComment().isBlank()) {
                    undelivered.append("<br/><i>").append(item.getComment()).append("</i>");
                }
                undelivered.append("</li>");
            }

            if (nonDelivered.isEmpty()
                    && "DELIVERED".equalsIgnoreCase(item.getOutcome())
                    && item.getComment() != null && !item.getComment().isBlank()) {
                driverNotes.append("<li><b>").append(label).append("</b> — Livré : <i>")
                        .append(item.getComment()).append("</i></li>");
            }
        }

        if (undelivered.length() == 0 && driverNotes.length() == 0) return null;

        StringBuilder sb = new StringBuilder("<b>ASM Track — Livraison partielle</b><br/>");
        if (undelivered.length() > 0) {
            sb.append("<b>Articles non livrés :</b><ul>").append(undelivered).append("</ul>");
        }
        if (driverNotes.length() > 0) {
            sb.append("<b>Notes chauffeur :</b><ul>").append(driverNotes).append("</ul>");
        }
        return sb.toString();
    }

    private String dispositionLabel(String disposition) {
        if (disposition == null) return "";
        return switch (disposition.toUpperCase()) {
            case "MISSING"   -> "Manquant";
            case "REFUSED"   -> "Refusé";
            case "DAMAGED"   -> "Endommagé";
            case "DELIVERED" -> "Livré";
            default          -> disposition;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Invoice helpers
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<Integer> extractInvoiceIds(Map<String, Object> soRead) {
        List<Integer> ids = new ArrayList<>();
        Object result = soRead != null ? soRead.get("result") : null;
        if (result instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> row) {
            Object inv = ((Map<String, Object>) row).get("invoice_ids");
            if (inv instanceof List<?> l) for (Object o : l) { Integer id = asInt(o); if (id != null) ids.add(id); }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private String extractFirstName(Map<String, Object> invRead) {
        Object result = invRead != null ? invRead.get("result") : null;
        if (result instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> row) {
            Object name = ((Map<String, Object>) row).get("name");
            return name != null ? String.valueOf(name) : null;
        }
        return null;
    }
}
