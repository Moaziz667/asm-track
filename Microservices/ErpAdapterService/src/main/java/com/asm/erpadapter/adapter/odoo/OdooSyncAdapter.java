package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Odoo implementation of {@link ErpSyncPort}.
 *
 * <p>Handles the four delivery lifecycle sync operations against Odoo 16/17
 * via JSON-RPC. Migrated from the monolithic OdooClient in DeliveryMicroservice.
 *
 * <p><b>Bean name:</b> {@code "odoo"} — used by {@code ErpProviderRouter} for routing.
 * Pass {@code erpProvider=odoo} on any sync endpoint to target this adapter.
 *
 * <p><b>Error handling:</b> All public methods catch exceptions internally and
 * return {@code false} or an empty result DTO rather than propagating errors.
 * Callers receive a boolean signal; retry logic lives in the DeliveryMicroservice.
 */
@Component("odoo")
@RequiredArgsConstructor
@Slf4j
public class OdooSyncAdapter implements ErpSyncPort {

    private final OdooJsonRpcClient rpc;

    // ══════════════════════════════════════════════════════════════════════════
    //  1. Order Cancellation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * @implNote Odoo: calls {@code sale.order.action_cancel}.
     *   Odoo may return a wizard ({@code res_model=sale.order.cancel}).
     *   The wizard is executed automatically before verifying the final state.
     *   Retried up to 3 times internally because cancel wizards are occasionally flaky.
     *   Final state verification: reads {@code sale.order.state} and checks for "cancel".
     */
    @Override
    public boolean syncOrderCancellation(String erpOrderId) {
        long start = System.currentTimeMillis();
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) {
            log.warn("syncOrderCancellation: could not resolve erpOrderId={}", erpOrderId);
            return false;
        }

        // Retry up to 3 times — Odoo cancel wizards are occasionally flaky
        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            if (attempts > 0) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            success = cancelSaleOrder(erpId);
            attempts++;
        }

        log.info("syncOrderCancellation: erpOrderId={} success={} attempts={} durationMs={}",
                erpOrderId, success, attempts, System.currentTimeMillis() - start);
        return success;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  2. Full Delivery
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * @implNote Odoo: confirms the sale order, locates the stock.picking linked
     *   to it (filtering out done/cancelled pickings), sets all stock.move
     *   {@code quantity_done = product_uom_qty}, then calls
     *   {@code stock.picking.button_validate}.
     *   Handles the {@code stock.immediate.transfer} and
     *   {@code stock.backorder.confirmation} wizard chains automatically.
     *   After transfer is done, writes {@code qty_delivered} on each
     *   {@code sale.order.line}.
     */
    @Override
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId) {
        long start = System.currentTimeMillis();
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) {
            log.warn("syncFullDelivery: could not resolve erpOrderId={}", erpOrderId);
            return false;
        }

        try {
            boolean transferOk = validateTransfer(erpId, backorderPickingId);
            if (!transferOk) {
                log.error("syncFullDelivery: transfer failed erpOrderId={}", erpOrderId);
                return false;
            }

            boolean deliveredSynced = syncSaleOrderLineDeliveredQuantities(erpId, null, true);
            log.info("syncFullDelivery: success in {}ms — erpOrderId={} deliveredSynced={}",
                    System.currentTimeMillis() - start, erpOrderId, deliveredSynced);
            return true;
        } catch (Exception e) {
            log.error("syncFullDelivery: failed erpOrderId={}", erpOrderId, e);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  3. Partial Delivery
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * @implNote Odoo: confirms the sale order, locates the stock.picking,
     *   reserves stock ({@code action_assign}), maps {@link ErpPartialItemDTO#getReferenceKey()}
     *   to Odoo product IDs (tries: numeric ID → default_code → barcode → name ilike),
     *   writes {@code qty_done} on both {@code stock.move.line} and {@code stock.move},
     *   calls {@code button_validate}, then handles the
     *   {@code stock.backorder.confirmation.process} wizard (creates backorder).
     *   Finally writes {@code qty_delivered} on sale order lines.
     */
    @Override
    @SuppressWarnings("unchecked")
    public ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId,
                                                             List<ErpPartialItemDTO> items) {
        long start = System.currentTimeMillis();
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) {
            log.warn("syncPartialDelivery: could not resolve erpOrderId={}", erpOrderId);
            return ErpPartialDeliveryResultDTO.builder().success(false).build();
        }

        try {
            confirmOrder(erpId);
            Map<String, Object> picking = findSinglePicking(erpId);
            if (picking == null) {
                log.warn("syncPartialDelivery: no picking found for erpOrderId={}", erpOrderId);
                return ErpPartialDeliveryResultDTO.builder().success(false).build();
            }

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            // Idempotency: if already done, look for existing backorder and return
            if ("done".equals(state)) {
                Integer existingBackorder = findBackorderPickingId(pickingId);
                log.info("syncPartialDelivery: picking already done, returning existingBackorder={}",
                        existingBackorder);
                return ErpPartialDeliveryResultDTO.builder()
                        .success(true).pickingId(pickingId).backorderPickingId(existingBackorder).build();
            }

            reserveStock(pickingId);

            // Resolve partial quantities from item SKUs to Odoo product IDs
            Map<Integer, Integer> productQtyDone = resolvePartialQuantities(items);
            if (productQtyDone.isEmpty() && items != null && !items.isEmpty()) {
                log.error("syncPartialDelivery: no products resolved from items for erpOrderId={}", erpOrderId);
                return ErpPartialDeliveryResultDTO.builder().success(false).pickingId(pickingId).build();
            }

            // Apply done quantities to both move lines and moves
            boolean mlUpdated = applyPartialQtyDoneToMoveLines(pickingId, productQtyDone);
            boolean mUpdated  = applyPartialQtyDoneToMoves(pickingId, productQtyDone);
            if (!mlUpdated && !mUpdated) {
                log.warn("syncPartialDelivery: no move lines updated for pickingId={}", pickingId);
                return ErpPartialDeliveryResultDTO.builder().success(false).pickingId(pickingId).build();
            }

            Map<String, Object> validateResponse = callValidatePicking(pickingId);
            if (validateResponse == null || validateResponse.containsKey("error")) {
                return ErpPartialDeliveryResultDTO.builder().success(false).pickingId(pickingId).build();
            }

            Object result = validateResponse.get("result");
            if (result instanceof Map<?, ?> action) {
                handleStockPickingAction(action, pickingId, false);
            }

            String finalState = readPickingState(pickingId);
            if (!"done".equalsIgnoreCase(finalState)) {
                log.warn("syncPartialDelivery: final state='{}' (expected done) pickingId={}",
                        finalState, pickingId);
                return ErpPartialDeliveryResultDTO.builder().success(false).pickingId(pickingId).build();
            }

            syncSaleOrderLineDeliveredQuantities(erpId, items, false);

            Integer backorderId = findBackorderPickingId(pickingId);
            log.info("syncPartialDelivery: success in {}ms — pickingId={} backorderId={}",
                    System.currentTimeMillis() - start, pickingId, backorderId);

            return ErpPartialDeliveryResultDTO.builder()
                    .success(true).pickingId(pickingId).backorderPickingId(backorderId).build();
        } catch (Exception e) {
            log.error("syncPartialDelivery: failed erpOrderId={}", erpOrderId, e);
            return ErpPartialDeliveryResultDTO.builder().success(false).build();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  4. Failure Note
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * @implNote Odoo: writes the {@code note} field on {@code sale.order} and also
     *   calls {@code message_post} to append a chatter entry with
     *   {@code subtype_xmlid="mail.mt_note"} so it appears in the activity log.
     *   No stock movements are made. Safe to call multiple times.
     */
    @Override
    public boolean syncFailure(String erpOrderId, String failureCode, String comment) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) {
            log.warn("syncFailure: could not resolve erpOrderId={}", erpOrderId);
            return false;
        }

        String note = "Delivery failed: " + (failureCode != null ? failureCode : "UNKNOWN")
                + (comment != null && !comment.isBlank() ? " — " + comment : "");

        boolean ok = addNoteToSaleOrder(erpId, note);
        log.info("syncFailure: erpOrderId={} success={} failureCode={}", erpOrderId, ok, failureCode);
        return ok;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Internal — Odoo RPC operations
    // ══════════════════════════════════════════════════════════════════════════

    /** Confirm a sale order (triggers stock transfer generation). */
    private void confirmOrder(Integer erpOrderId) {
        rpc.callRpc(rpc.buildArgs("sale.order", "action_confirm", List.of(List.of(erpOrderId))));
    }

    @SuppressWarnings("unchecked")
    private boolean cancelSaleOrder(Integer erpOrderId) {
        try {
            Map<String, Object> response = rpc.callRpc(
                    rpc.buildArgs("sale.order", "action_cancel", List.of(List.of(erpOrderId))));
            if (response == null || response.containsKey("error")) return false;

            Object result = response.get("result");
            if (result instanceof Map<?, ?> action) {
                if (!handleSaleCancelAction(action, erpOrderId)) return false;
            }

            String state = readSaleOrderState(erpOrderId);
            return "cancel".equalsIgnoreCase(state);
        } catch (Exception e) {
            log.error("cancelSaleOrder exception erpOrderId={}", erpOrderId, e);
            return false;
        }
    }

    private boolean addNoteToSaleOrder(Integer erpOrderId, String note) {
        try {
            Map<String, Object> vals = new HashMap<>();
            vals.put("note", note != null ? note : "");

            Map<String, Object> response = rpc.callRpc(
                    rpc.buildArgs("sale.order", "write", List.of(List.of(erpOrderId), vals)));
            if (response == null || response.containsKey("error")) return false;

            // Post in chatter so ERP users see it in the activity log
            Map<String, Object> chatterVals = new HashMap<>();
            chatterVals.put("body", note != null ? note : "");
            chatterVals.put("message_type", "comment");
            chatterVals.put("subtype_xmlid", "mail.mt_note");
            rpc.callRpc(rpc.buildArgs("sale.order", "message_post",
                    List.of(List.of(erpOrderId)), chatterVals));

            return true;
        } catch (Exception e) {
            log.error("addNoteToSaleOrder exception erpOrderId={}", erpOrderId, e);
            return false;
        }
    }

    // ── Stock / Transfer operations ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean validateTransfer(Integer erpOrderId, Integer explicitPickingId) {
        try {
            confirmOrder(erpOrderId);

            Map<String, Object> picking = (explicitPickingId != null)
                    ? findPickingById(explicitPickingId)
                    : findSinglePicking(erpOrderId);
            if (picking == null) return false;

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");
            if ("done".equals(state)) return true;

            reserveStock(pickingId);
            setFullQuantityDoneOnMoves(pickingId);

            Map<String, Object> validateResponse = callValidatePicking(pickingId);
            if (validateResponse == null || validateResponse.containsKey("error")) return false;

            Object result = validateResponse.get("result");
            if (result instanceof Map<?, ?> action) {
                if (!handleStockPickingAction(action, pickingId, true)) return false;
            }

            String finalState = readPickingState(pickingId);
            return "done".equalsIgnoreCase(finalState);
        } catch (Exception e) {
            log.error("validateTransfer exception erpOrderId={}", erpOrderId, e);
            return false;
        }
    }

    private void reserveStock(Integer pickingId) {
        rpc.callRpc(rpc.buildArgs("stock.picking", "action_assign", List.of(List.of(pickingId))));
    }

    @SuppressWarnings("unchecked")
    private void setFullQuantityDoneOnMoves(Integer pickingId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "product_uom_qty", "quantity_done"));

        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))), kwargs));
        if (response == null || response.containsKey("error")) return;

        List<Map<String, Object>> moves = (List<Map<String, Object>>) response.get("result");
        if (moves == null) return;

        for (Map<String, Object> move : moves) {
            Integer moveId = asInt(move.get("id"));
            Object plannedQty = move.get("product_uom_qty");
            if (moveId == null || !(plannedQty instanceof Number)) continue;

            rpc.callRpc(rpc.buildArgs("stock.move", "write",
                    List.of(List.of(moveId), Map.of("quantity_done", plannedQty))));
        }
    }

    private Map<String, Object> callValidatePicking(Integer pickingId) {
        return rpc.callRpc(rpc.buildArgs("stock.picking", "button_validate", List.of(List.of(pickingId))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findSinglePicking(Integer erpOrderId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "state", "origin", "sale_id"));
        kwargs.put("limit", 1);

        // Primary: by sale_id linkage
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("sale_id", "=", erpOrderId),
                        List.of("state", "not in", List.of("done", "cancel")))), kwargs));

        if (response != null && !response.containsKey("error")) {
            List<Map<String, Object>> pickings = (List<Map<String, Object>>) response.get("result");
            if (pickings != null && !pickings.isEmpty()) return pickings.get(0);
        }

        // Fallback: by origin name (e.g. "S00042")
        String saleName = getSaleOrderName(erpOrderId);
        if (saleName == null || saleName.isBlank()) return null;

        response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("origin", "ilike", saleName),
                        List.of("state", "not in", List.of("done", "cancel")))), kwargs));
        if (response == null || response.containsKey("error")) return null;
        List<Map<String, Object>> pickings = (List<Map<String, Object>>) response.get("result");
        return (pickings != null && !pickings.isEmpty()) ? pickings.get(0) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findPickingById(Integer pickingId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "state", "origin", "sale_id"));
        kwargs.put("limit", 1);

        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("id", "=", pickingId))), kwargs));
        if (response == null || response.containsKey("error")) return null;
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        return (rows != null && !rows.isEmpty()) ? rows.get(0) : null;
    }

    @SuppressWarnings("unchecked")
    private Integer findBackorderPickingId(Integer originPickingId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "state", "backorder_id"));
        kwargs.put("limit", 1);

        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("backorder_id", "=", originPickingId))), kwargs));
        if (response == null || response.containsKey("error")) return null;
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        return (rows != null && !rows.isEmpty()) ? asInt(rows.get(0).get("id")) : null;
    }

    @SuppressWarnings("unchecked")
    private String readPickingState(Integer pickingId) {
        Map<String, Object> kwargs = Map.of("fields", List.of("state"), "limit", 1);
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("id", "=", pickingId))), kwargs));
        if (response == null || response.containsKey("error")) return null;
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) return null;
        Object state = rows.get(0).get("state");
        return state != null ? String.valueOf(state) : null;
    }

    @SuppressWarnings("unchecked")
    private String readSaleOrderState(Integer erpOrderId) {
        Map<String, Object> kwargs = Map.of("fields", List.of("state"), "limit", 1);
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "search_read",
                List.of(List.of(List.of("id", "=", erpOrderId))), kwargs));
        if (response == null || response.containsKey("error")) return null;
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) return null;
        Object state = rows.get(0).get("state");
        return state != null ? String.valueOf(state) : null;
    }

    @SuppressWarnings("unchecked")
    private String getSaleOrderName(Integer erpOrderId) {
        Map<String, Object> kwargs = Map.of("fields", List.of("id", "name"), "limit", 1);
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "search_read",
                List.of(List.of(List.of("id", "=", erpOrderId))), kwargs));
        if (response == null || response.containsKey("error")) return null;
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) return null;
        Object name = rows.get(0).get("name");
        return name != null ? String.valueOf(name) : null;
    }

    // ── Partial quantity helpers ────────────────────────────────────────────

    private Map<Integer, Integer> resolvePartialQuantities(List<ErpPartialItemDTO> items) {
        Map<Integer, Integer> productQtyDone = new HashMap<>();
        if (items == null) return productQtyDone;
        for (ErpPartialItemDTO item : items) {
            if (item == null || item.getQuantityDone() == null || item.getQuantityDone() <= 0) continue;
            Integer productId = resolveProductIdFromSku(item.getReferenceKey());
            if (productId != null) {
                productQtyDone.merge(productId, item.getQuantityDone(), Integer::sum);
            }
        }
        return productQtyDone;
    }

    @SuppressWarnings("unchecked")
    private Integer resolveProductIdFromSku(String sku) {
        if (sku == null || sku.isBlank()) return null;
        String normalized = sku.trim();
        // Fast path: numeric product ID
        try { return Integer.parseInt(normalized); }
        catch (NumberFormatException ignored) {}

        // Search by default_code, barcode, then name
        try {
            Map<String, Object> kwargs = Map.of("fields", List.of("id"), "limit", 1);
            Map<String, Object> response = rpc.callRpc(rpc.buildArgs("product.product", "search_read",
                    List.of(List.of("|", "|",
                            List.of("default_code", "=", normalized),
                            List.of("barcode", "=", normalized),
                            List.of("name", "ilike", normalized))), kwargs));
            if (response != null && !response.containsKey("error")) {
                List<Map<String, Object>> rows =
                        (List<Map<String, Object>>) response.getOrDefault("result", List.of());
                if (!rows.isEmpty()) {
                    Integer id = asInt(rows.get(0).get("id"));
                    if (id != null) return id;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean applyPartialQtyDoneToMoveLines(Integer pickingId,
                                                    Map<Integer, Integer> productQtyDone) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "product_id", "qty_done", "reserved_qty", "reserved_uom_qty"));

        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.move.line", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))), kwargs));
        if (response == null || response.containsKey("error")) return false;

        List<Map<String, Object>> moveLines = (List<Map<String, Object>>) response.get("result");
        if (moveLines == null || moveLines.isEmpty()) return false;

        Map<Integer, Integer> remaining = new HashMap<>(productQtyDone);
        int updated = 0;

        for (Map<String, Object> line : moveLines) {
            Integer lineId    = asInt(line.get("id"));
            Integer productId = asRelId(line.get("product_id"));
            if (lineId == null || productId == null) continue;

            int rem = Math.max(remaining.getOrDefault(productId, 0), 0);
            Integer reservedRaw    = asInt(line.get("reserved_qty"));
            Integer reservedUomRaw = asInt(line.get("reserved_uom_qty"));
            int lineReserved = reservedRaw != null && reservedRaw > 0 ? reservedRaw
                    : reservedUomRaw != null && reservedUomRaw > 0 ? reservedUomRaw : rem;

            int lineDone = Math.min(rem, lineReserved);
            remaining.put(productId, Math.max(rem - lineDone, 0));

            Map<String, Object> writeRes = rpc.callRpc(
                    rpc.buildArgs("stock.move.line", "write",
                            List.of(List.of(lineId), Map.of("qty_done", lineDone))));
            if (writeRes != null && !writeRes.containsKey("error")) updated++;
        }
        return updated > 0;
    }

    @SuppressWarnings("unchecked")
    private boolean applyPartialQtyDoneToMoves(Integer pickingId,
                                                Map<Integer, Integer> productQtyDone) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "product_id", "product_uom_qty", "quantity_done"));

        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))), kwargs));
        if (response == null || response.containsKey("error")) return false;

        List<Map<String, Object>> moves = (List<Map<String, Object>>) response.get("result");
        if (moves == null || moves.isEmpty()) return false;

        int updated = 0;
        for (Map<String, Object> move : moves) {
            Integer moveId    = asInt(move.get("id"));
            Integer productId = asRelId(move.get("product_id"));
            if (moveId == null || productId == null) continue;

            BigDecimal planned = asBigDecimal(move.get("product_uom_qty"));
            int qtyDone = Math.min(
                    Math.max(productQtyDone.getOrDefault(productId, 0), 0),
                    planned.intValue());

            Map<String, Object> writeRes = rpc.callRpc(
                    rpc.buildArgs("stock.move", "write",
                            List.of(List.of(moveId), Map.of("quantity_done", qtyDone))));
            if (writeRes != null && !writeRes.containsKey("error")) updated++;
        }
        return updated > 0;
    }

    @SuppressWarnings("unchecked")
    private boolean syncSaleOrderLineDeliveredQuantities(Integer erpOrderId,
                                                          List<ErpPartialItemDTO> partialItems,
                                                          boolean fullDelivery) {
        try {
            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("fields", List.of("id", "product_id", "product_uom_qty", "qty_delivered", "display_type"));

            Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order.line", "search_read",
                    List.of(List.of(List.of("order_id", "=", erpOrderId),
                            List.of("display_type", "=", false))), kwargs));
            if (response == null || response.containsKey("error")) return false;

            List<Map<String, Object>> lines = (List<Map<String, Object>>) response.get("result");
            if (lines == null || lines.isEmpty()) return false;

            Map<Integer, Integer> partialDone = new HashMap<>();
            if (!fullDelivery && partialItems != null) {
                for (ErpPartialItemDTO item : partialItems) {
                    Integer productId = resolveProductIdFromSku(item != null ? item.getReferenceKey() : null);
                    if (productId != null) {
                        partialDone.put(productId, item.getQuantityDone() != null
                                ? Math.max(item.getQuantityDone(), 0) : 0);
                    }
                }
            }

            for (Map<String, Object> line : lines) {
                Integer lineId    = asInt(line.get("id"));
                Integer productId = asRelId(line.get("product_id"));
                if (lineId == null || productId == null) continue;

                BigDecimal planned = asBigDecimal(line.get("product_uom_qty"));
                BigDecimal target  = fullDelivery
                        ? planned.max(BigDecimal.ZERO)
                        : BigDecimal.valueOf(Math.max(partialDone.getOrDefault(productId, 0), 0L))
                                .min(planned);

                BigDecimal current = asBigDecimal(line.get("qty_delivered"));
                if (current.compareTo(target) == 0) continue;

                rpc.callRpc(rpc.buildArgs("sale.order.line", "write",
                        List.of(List.of(lineId), Map.of("qty_delivered", target))));
            }
            return true;
        } catch (Exception e) {
            log.warn("syncSaleOrderLineDeliveredQuantities failed erpOrderId={}", erpOrderId, e);
            return false;
        }
    }

    // ── Wizard handlers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean handleSaleCancelAction(Map<?, ?> action, Integer erpOrderId) {
        if (!"sale.order.cancel".equals(action.get("res_model"))) return false;

        Integer wizardId = null;
        Object resId = action.get("res_id");
        if (resId instanceof Number n) wizardId = n.intValue();

        if (wizardId == null) {
            Map<String, Object> createRes = rpc.callRpc(rpc.buildArgs("sale.order.cancel", "create",
                    List.of(Map.of("order_id", erpOrderId))));
            if (createRes == null || createRes.containsKey("error") ||
                    !(createRes.get("result") instanceof Number n)) return false;
            wizardId = n.intValue();
        }

        Map<String, Object> applyRes = rpc.callRpc(
                rpc.buildArgs("sale.order.cancel", "action_cancel", List.of(List.of(wizardId))));
        return applyRes != null && !applyRes.containsKey("error");
    }

    @SuppressWarnings("unchecked")
    private boolean handleStockPickingAction(Map<?, ?> action, Integer pickingId,
                                              boolean cancelBackorder) {
        Object resModelObj = action.get("res_model");
        if (!(resModelObj instanceof String resModel)) return false;

        Map<String, Object> kwargs = new HashMap<>();
        if (action.get("context") instanceof Map<?, ?> c) kwargs.put("context", c);

        Integer wizardId = action.get("res_id") instanceof Number n ? n.intValue() : null;

        if ("stock.immediate.transfer".equals(resModel)) {
            if (wizardId == null) {
                Map<String, Object> createRes = rpc.callRpc(
                        rpc.buildArgs("stock.immediate.transfer", "create",
                                List.of(Map.of("pick_ids", List.of(List.of(6, 0, List.of(pickingId))))),
                                kwargs));
                if (createRes == null || !(createRes.get("result") instanceof Number n2)) return false;
                wizardId = n2.intValue();
            }
            Map<String, Object> processRes = rpc.callRpc(
                    rpc.buildArgs("stock.immediate.transfer", "process",
                            List.of(List.of(wizardId)), kwargs));
            if (processRes == null || processRes.containsKey("error")) return false;
            Object next = processRes.get("result");
            if (next instanceof Map<?, ?> nextAction) return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            return true;
        }

        if ("stock.backorder.confirmation".equals(resModel)) {
            if (wizardId == null) {
                Map<String, Object> createRes = rpc.callRpc(
                        rpc.buildArgs("stock.backorder.confirmation", "create",
                                List.of(Map.of("pick_ids", List.of(List.of(6, 0, List.of(pickingId))))),
                                kwargs));
                if (createRes == null || !(createRes.get("result") instanceof Number n2)) return false;
                wizardId = n2.intValue();
            }
            String method = cancelBackorder ? "process_cancel_backorder" : "process";
            Map<String, Object> processRes = rpc.callRpc(
                    rpc.buildArgs("stock.backorder.confirmation", method,
                            List.of(List.of(wizardId)), kwargs));
            if (processRes == null || processRes.containsKey("error")) return false;
            Object next = processRes.get("result");
            if (next instanceof Map<?, ?> nextAction) return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            return true;
        }

        if ("confirm.stock.sms".equals(resModel)) {
            if (wizardId == null) return false;
            Map<String, Object> noSmsRes = rpc.callRpc(
                    rpc.buildArgs("confirm.stock.sms", "dont_send_sms",
                            List.of(List.of(wizardId)), kwargs));
            if (noSmsRes == null || noSmsRes.containsKey("error")) return false;
            Object next = noSmsRes.get("result");
            if (next instanceof Map<?, ?> nextAction) return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            Map<String, Object> revalidate = callValidatePicking(pickingId);
            if (revalidate == null || revalidate.containsKey("error")) return false;
            Object reResult = revalidate.get("result");
            if (reResult instanceof Map<?, ?> nextAction) return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            return true;
        }

        log.warn("handleStockPickingAction: unhandled wizard model={}", resModel);
        return false;
    }

    // ── ID resolution ───────────────────────────────────────────────────────

    /**
     * Resolve an ERP order ID to an Odoo numeric database ID.
     *
     * <p>Accepts: numeric ID string, SO name (e.g. "S00042"), name with backorder suffix "-B".
     */
    @SuppressWarnings("unchecked")
    Integer resolveErpId(String erpOrderId) {
        if (erpOrderId == null || erpOrderId.isBlank()) return null;
        String normalized = erpOrderId.trim();
        if (normalized.contains("-B")) normalized = normalized.substring(0, normalized.indexOf("-B"));

        // Fast path: numeric ID
        try { return Integer.parseInt(normalized); }
        catch (NumberFormatException ignored) {}

        // Search by exact SO name then by pattern
        try {
            Map<String, Object> kwargs = Map.of("fields", List.of("id"), "limit", 1);
            Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "search_read",
                    List.of(List.of(List.of("name", "=", normalized))), kwargs));
            if (response != null && !response.containsKey("error")) {
                List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
                if (rows != null && !rows.isEmpty() && rows.get(0).get("id") instanceof Number n)
                    return n.intValue();
            }

            response = rpc.callRpc(rpc.buildArgs("sale.order", "search_read",
                    List.of(List.of(List.of("name", "ilike", normalized))), kwargs));
            if (response != null && !response.containsKey("error")) {
                List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
                if (rows != null && !rows.isEmpty() && rows.get(0).get("id") instanceof Number n)
                    return n.intValue();
            }
        } catch (Exception e) {
            log.warn("resolveErpId exception ref={}", normalized, e);
        }
        return null;
    }
}
