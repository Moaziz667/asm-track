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
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        try {
            boolean transferOk = validateTransfer(erpId, backorderPickingId);
            if (!transferOk) return false;

            syncSaleOrderLineDeliveredQuantities(erpId, null, true);
            return true;
        } catch (Exception e) {
            log.error("syncFullDelivery failed", e);
            return false;
        }
    }

    private ErpPartialDeliveryResultDTO doSyncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return ErpPartialDeliveryResultDTO.builder().success(false).build();

        try {
            confirmOrder(erpId);
            Map<String, Object> picking = findSinglePicking(erpId);
            if (picking == null) return ErpPartialDeliveryResultDTO.builder().success(false).build();

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            if ("done".equals(state)) {
                // If this is a retry and the picking is already done, we must still ensure
                // the sale order lines reflect the delivered quantities before returning.
                syncSaleOrderLineDeliveredQuantities(erpId, items, false);
                return ErpPartialDeliveryResultDTO.builder()
                        .success(true).pickingId(pickingId).backorderPickingId(findBackorderPickingId(pickingId)).build();
            }

            reserveStock(pickingId);
            Map<Integer, Integer> productQtyDone = resolvePartialQuantities(items);
            applyPartialQtyDoneToMoveLines(pickingId, productQtyDone);
            applyPartialQtyDoneToMoves(pickingId, productQtyDone);

            Map<String, Object> validateResponse = callValidatePicking(pickingId);
            if (validateResponse == null || validateResponse.containsKey("error")) return ErpPartialDeliveryResultDTO.builder().success(false).build();

            syncSaleOrderLineDeliveredQuantities(erpId, items, false);
            return ErpPartialDeliveryResultDTO.builder()
                    .success(true).pickingId(pickingId).backorderPickingId(findBackorderPickingId(pickingId)).build();
        } catch (Exception e) {
            log.error("syncPartialDelivery failed", e);
            return ErpPartialDeliveryResultDTO.builder().success(false).build();
        }
    }

    private boolean doSyncFailure(String erpOrderId, String failureCode, String comment) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        String note = "Delivery failed: " + (failureCode != null ? failureCode : "UNKNOWN") + " — " + comment;
        addNoteToSaleOrder(erpId, note);
        tagOrderAsDeliveryFailed(erpId);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Odoo JSON-RPC Low-Level (Private)
    // ══════════════════════════════════════════════════════════════════════════

    private void confirmOrder(Integer erpOrderId) {
        rpc.callRpc(rpc.buildArgs("sale.order", "action_confirm", List.of(List.of(erpOrderId))));
    }

    private boolean cancelSaleOrder(Integer erpOrderId) {
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "action_cancel", List.of(List.of(erpOrderId))));
        if (resp != null && resp.containsKey("error")) {
            log.warn("Failed to cancel sale order {}: {}", erpOrderId, resp.get("error"));
            return false;
        }

        // Odoo returns a confirmation wizard (sale.order.cancel) instead of cancelling directly.
        // Detect it and confirm programmatically.
        Object result = resp != null ? resp.get("result") : null;
        if (result instanceof Map<?, ?> resultMap) {
            String resModel = (String) resultMap.get("res_model");
            if ("sale.order.cancel".equals(resModel)) {
                confirmCancelWizard(erpOrderId);
            }
        }

        String state = readSaleOrderState(erpOrderId);
        return "cancel".equalsIgnoreCase(state);
    }

    private void confirmCancelWizard(Integer erpOrderId) {
        Map<String, Object> createResp = rpc.callRpc(
                rpc.buildArgs("sale.order.cancel", "create", List.of(Map.of("order_id", erpOrderId))));
        Object wizardId = createResp != null ? createResp.get("result") : null;
        if (wizardId instanceof Number wid) {
            rpc.callRpc(rpc.buildArgs("sale.order.cancel", "action_cancel",
                    List.of(List.of(wid.intValue()))));
        } else {
            log.warn("Could not create sale.order.cancel wizard for order {}", erpOrderId);
        }
    }

    private boolean validateTransfer(Integer erpOrderId, Integer explicitPickingId) {
        confirmOrder(erpOrderId);
        Map<String, Object> picking = (explicitPickingId != null) ? findPickingById(explicitPickingId) : findSinglePicking(erpOrderId);
        if (picking == null) return false;

        Integer pickingId = ((Number) picking.get("id")).intValue();
        if ("done".equals(picking.get("state"))) return true;

        reserveStock(pickingId);
        setFullQuantityDoneOnMoves(pickingId);
        rpc.callRpc(rpc.buildArgs("stock.picking", "button_validate", List.of(List.of(pickingId))));
        return "done".equalsIgnoreCase(readPickingState(pickingId));
    }

    private void reserveStock(Integer pickingId) {
        rpc.callRpc(rpc.buildArgs("stock.picking", "action_assign", List.of(List.of(pickingId))));
    }

    private void setFullQuantityDoneOnMoves(Integer pickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))), Map.of("fields", List.of("id", "product_uom_qty"))));
        List<Map<String, Object>> moves = (List<Map<String, Object>>) response.get("result");
        if (moves == null) return;
        for (Map<String, Object> move : moves) {
            rpc.callRpc(rpc.buildArgs("stock.move", "write", List.of(List.of(move.get("id")), Map.of("quantity_done", move.get("product_uom_qty")))));
        }
    }

    private Map<String, Object> callValidatePicking(Integer pickingId) {
        return rpc.callRpc(rpc.buildArgs("stock.picking", "button_validate", List.of(List.of(pickingId))));
    }

    private Map<String, Object> findSinglePicking(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("sale_id", "=", erpOrderId), List.of("state", "not in", List.of("done", "cancel")))), Map.of("fields", List.of("id", "state"), "limit", 1)));
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
            Integer pid = resolveProductIdFromSku(item.getReferenceKey());
            if (pid != null) qtyMap.merge(pid, item.getQuantityDone(), Integer::sum);
        }
        return qtyMap;
    }

    private Integer resolveProductIdFromSku(String sku) {
        try {
            // ALWAYS search by default_code first. Many SKUs are purely numerical (e.g. "31", "12345").
            // If we blindly parse them as integers, we'll try to update the wrong product_id.
            Map<String, Object> res = rpc.callRpc(rpc.buildArgs("product.product", "search", 
                List.of(List.of(List.of("default_code", "=", sku))), Map.of("limit", 1)));
            List<Integer> ids = (List<Integer>) res.get("result");
            if (ids != null && !ids.isEmpty()) {
                return ids.get(0);
            }
            // Fallback: if it's purely numerical, it might actually be an Odoo product_id passed directly
            return Integer.parseInt(sku);
        } catch (Exception e) {
            log.warn("Could not resolve product ID for SKU: {}", sku);
            return null;
        }
    }

    private void applyPartialQtyDoneToMoveLines(Integer pickingId, Map<Integer, Integer> productQtyDone) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.move.line", "search_read", List.of(List.of(List.of("picking_id", "=", pickingId))), Map.of("fields", List.of("id", "product_id"))));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) response.get("result");
        for (Map<String, Object> line : lines) {
            Integer pid = asRelId(line.get("product_id"));
            if (productQtyDone.containsKey(pid)) {
                rpc.callRpc(rpc.buildArgs("stock.move.line", "write", List.of(List.of(line.get("id")), Map.of("qty_done", productQtyDone.get(pid)))));
            }
        }
    }

    private void applyPartialQtyDoneToMoves(Integer pickingId, Map<Integer, Integer> productQtyDone) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.move", "search_read", List.of(List.of(List.of("picking_id", "=", pickingId))), Map.of("fields", List.of("id", "product_id"))));
        List<Map<String, Object>> moves = (List<Map<String, Object>>) response.get("result");
        for (Map<String, Object> move : moves) {
            Integer pid = asRelId(move.get("product_id"));
            if (productQtyDone.containsKey(pid)) {
                rpc.callRpc(rpc.buildArgs("stock.move", "write", List.of(List.of(move.get("id")), Map.of("quantity_done", productQtyDone.get(pid)))));
            }
        }
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

    private void tagOrderAsDeliveryFailed(Integer erpId) {
        // Logic to add 'Delivery Failed' tag
    }

    private Integer asInt(Object o) { return o instanceof Number n ? n.intValue() : null; }
    private Integer asRelId(Object o) { if (o instanceof List<?> l && !l.isEmpty()) return asInt(l.get(0)); return null; }
}
