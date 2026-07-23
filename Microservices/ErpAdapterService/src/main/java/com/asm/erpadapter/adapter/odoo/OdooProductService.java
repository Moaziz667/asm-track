package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.dto.ErpReturnItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.asInt;
import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.asRelId;

/**
 * Focused service for Odoo product and quantity operations.
 * Wraps product resolution, partial delivery qty writes, sale-order-line
 * delivered-quantity sync, and return-quantity application.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooProductService {

    private final OdooJsonRpcClient rpc;
    private final CapabilityResolver capabilityResolver;

    /**
     * Resolves an Odoo {@code product.product} ID from a SKU ({@code default_code}).
     * Falls back to exact name match for service products that have no internal reference.
     *
     * @param sku      the product internal reference (default_code), may be null
     * @param itemName the product name, may be null
     * @return the Odoo product ID, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Integer resolveProductId(String sku, String itemName) {
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
     * Resolves a list of partial-delivery items to an Odoo product-ID → quantity-done map.
     *
     * @param items the partial items carrying SKU / product name and quantity done
     * @return map of Odoo product ID to total quantity done
     */
    public Map<Integer, Integer> resolvePartialQuantities(List<ErpPartialItemDTO> items) {
        Map<Integer, Integer> qtyMap = new HashMap<>();
        for (ErpPartialItemDTO item : items) {
            Integer pid = resolveProductId(item.getReferenceKey(), item.getItemName());
            if (pid != null) qtyMap.merge(pid, item.getQuantityDone(), Integer::sum);
        }
        return qtyMap;
    }

    /**
     * Writes {@code qty_done} to each {@code stock.move.line} for a partial delivery.
     * Matches move lines to items using three strategies in order: numeric product ID,
     * SKU string, and product name. Move lines for products not in the items list are
     * set to 0.
     *
     * @param pickingId the Odoo picking ID
     * @param items     the partial items
     * @return total qty_done written across all move lines
     */
    @SuppressWarnings("unchecked")
    public int applyPartialQtyDoneToMoveLines(Integer pickingId, List<ErpPartialItemDTO> items) {
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

        List<Integer> productIds = lines.stream()
                .map(l -> asRelId(l.get("product_id")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
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

        String doneQtyField = capabilityResolver.resolve(CanonicalCapability.DONE_QUANTITY);
        int totalWritten = 0;
        for (Map<String, Object> line : lines) {
            Integer pid   = asRelId(line.get("product_id"));
            String  sku   = pidToSku.get(pid);
            String  pName = pidToName.get(pid);

            Integer qty = null;
            if (pid  != null && pidToQty.containsKey(pid))   qty = pidToQty.get(pid);
            if (qty  == null && sku  != null)                 qty = skuToQty.get(sku);
            if (qty == null && sku != null) {
                final String bracketPrefix = "[" + sku + "]";
                qty = skuToQty.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(bracketPrefix))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(null);
            }
            if (qty  == null && pName != null)                qty = nameToQty.get(pName);
            if (qty  == null) qty = 0;

            Map<String, Object> writeResp = rpc.callRpc(rpc.buildArgs("stock.move.line", "write",
                    List.of(List.of(line.get("id")), Map.of(doneQtyField, qty))));
            log.info("provider=odoo operation=applyPartialQty pickingId={} lineId={} productId={} sku={} field={} qty={} writeResult={}",
                    pickingId, line.get("id"), pid, sku, doneQtyField, qty, writeResp != null ? writeResp.get("result") : "null");
            totalWritten += qty;
        }
        return totalWritten;
    }

    /**
     * Synchronizes {@code qty_delivered} on each {@code sale.order.line} for a partial delivery.
     * For full delivery, Odoo handles this automatically on picking validation so no write is issued.
     *
     * @param erpOrderId the Odoo sale order ID
     * @param items      the partial items
     * @param full       true if this is a full delivery (Odoo handles qty_delivered automatically)
     */
    @SuppressWarnings("unchecked")
    public void syncSaleOrderLineDeliveredQuantities(Integer erpOrderId, List<ErpPartialItemDTO> items, boolean full) {
        try {
            if (full) {
                log.debug("syncSaleOrderLineDeliveredQuantities: full delivery for erpOrderId={} — Odoo handles qty_delivered automatically", erpOrderId);
                return;
            }

            if (items == null || items.isEmpty()) return;
            Map<Integer, Integer> productQtyDone = resolvePartialQuantities(items);
            if (productQtyDone.isEmpty()) {
                log.warn("syncSaleOrderLineDeliveredQuantities: no product IDs resolved for erpOrderId={}", erpOrderId);
                return;
            }

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
            log.warn("syncSaleOrderLineDeliveredQuantities failed for erpOrderId={}: {}", erpOrderId, e.getMessage(), e);
        }
    }

    /**
     * Writes return quantities on {@code stock.return.picking.line} records matched by SKU.
     * Lines for products not present in the RMA items list are set to 0.
     *
     * @param wizardId the Odoo stock.return.picking wizard ID
     * @param items    the return items carrying SKU and quantity
     */
    @SuppressWarnings("unchecked")
    public void applyReturnQuantities(Integer wizardId, List<ErpReturnItemDTO> items) {
        if (items == null || items.isEmpty()) return;
        Map<String, Integer> skuToQty = new HashMap<>();
        for (ErpReturnItemDTO it : items) {
            if (it.getSku() != null && !it.getSku().isBlank() && it.getQuantity() != null) {
                skuToQty.merge(it.getSku().trim(), it.getQuantity(), Integer::sum);
            }
        }
        if (skuToQty.isEmpty()) return;

        Map<String, Object> linesResp = rpc.callRpc(rpc.buildArgs("stock.return.picking.line", "search_read",
                List.of(List.of(List.of("wizard_id", "=", wizardId))),
                Map.of("fields", List.of("id", "product_id", "quantity"))));
        List<Map<String, Object>> lines = linesResp != null ? (List<Map<String, Object>>) linesResp.get("result") : null;
        if (lines == null || lines.isEmpty()) {
            log.info("provider=odoo operation=applyReturnQuantities wizardId={} action=no_lines", wizardId);
            return;
        }
        List<Integer> productIds = lines.stream().map(l -> asRelId(l.get("product_id")))
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Integer, String> pidToSku = new HashMap<>();
        if (!productIds.isEmpty()) {
            Map<String, Object> prodResp = rpc.callRpc(rpc.buildArgs("product.product", "search_read",
                    List.of(List.of(List.of("id", "in", productIds))),
                    Map.of("fields", List.of("id", "default_code"))));
            List<Map<String, Object>> prods = prodResp != null ? (List<Map<String, Object>>) prodResp.get("result") : null;
            if (prods != null) for (Map<String, Object> p : prods) {
                Integer pid = asInt(p.get("id"));
                Object dc = p.get("default_code");
                if (pid != null && dc instanceof String s && !s.isBlank()) pidToSku.put(pid, s.trim());
            }
        }
        for (Map<String, Object> line : lines) {
            Integer pid = asRelId(line.get("product_id"));
            String sku = pid != null ? pidToSku.get(pid) : null;
            int qty = (sku != null && skuToQty.containsKey(sku)) ? skuToQty.get(sku) : 0;
            rpc.callRpc(rpc.buildArgs("stock.return.picking.line", "write",
                    List.of(List.of(line.get("id")), Map.of("quantity", qty))));
        }
        log.info("provider=odoo operation=applyReturnQuantities wizardId={} lines={} skuToQty={}", wizardId, lines.size(), skuToQty);
    }
}
