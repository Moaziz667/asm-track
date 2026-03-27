package com.asm.delivery.odoo;

import com.asm.delivery.entity.OrderItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OdooClient {

    @Value("${odoo.url:http://host.docker.internal:8069/jsonrpc}")
    private String odooUrl;

    @Value("${odoo.db:odoo}")
    private String odooDb;

    @Value("${odoo.uid:2}")
    private int odooUid;

    @Value("${odoo.password:admin}")
    private String odooPassword;

    private final RestTemplate restTemplate;

    public OdooClient(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    // ── Search product by name → returns product.product id or null ───────────

    @SuppressWarnings("unchecked")
    public Integer searchProductByName(String name) {
        try {
            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "product.product", "search",
                    List.of(List.of(List.of("name", "ilike", name)))
            );
            Map<String, Object> response = callRpc(args);
            if (response == null || response.containsKey("error")) return null;
            List<Number> ids = (List<Number>) response.get("result");
            if (ids == null || ids.isEmpty()) return null;
            return ids.get(0).intValue();
        } catch (Exception e) {
            log.warn("Odoo searchProductByName failed for name={}: {}", name, e.getMessage());
            return null;
        }
    }

    // ── Create sale.order ─────────────────────────────────────────────────────

    public Integer createSaleOrder(Integer partnerOdooId, List<OrderItem> items, String note) {
        long start = System.currentTimeMillis();
        try {
            List<List<Object>> orderLines = new ArrayList<>();
            if (items != null) {
                for (OrderItem item : items) {
                    Map<String, Object> lineVals = new HashMap<>();
                    String itemName = item.getName() != null ? item.getName() : "Item";
                    Integer productId = searchProductByName(itemName);
                    if (productId != null) {
                        // Real product found — use product_id with quantity and price
                        lineVals.put("product_id",      productId);
                        lineVals.put("name",            itemName);
                        lineVals.put("product_uom_qty", item.getQuantity() != null ? item.getQuantity() : 1);
                        lineVals.put("price_unit",      item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
                    } else {
                        // Product not found in Odoo — fall back to note line
                        log.warn("Product '{}' not found in Odoo — creating as note line", itemName);
                        lineVals.put("display_type", "line_note");
                        lineVals.put("name", itemName
                                + (item.getQuantity()  != null ? " × " + item.getQuantity()  : "")
                                + (item.getUnitPrice() != null ? " @ " + item.getUnitPrice() : ""));
                    }
                    orderLines.add(List.of(0, 0, lineVals));
                }
            }

            Map<String, Object> orderVals = new HashMap<>();
            orderVals.put("partner_id", partnerOdooId);
            orderVals.put("order_line", orderLines);
            if (note != null) orderVals.put("note", note);

            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order", "create",
                    List.of(orderVals)
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.error("Odoo createSaleOrder failed in {}ms — response: {}", ms, response);
                return null;
            }

            Object result = response.get("result");
            if (result == null) {
                log.error("Odoo createSaleOrder returned null result in {}ms", ms);
                return null;
            }

            Integer odooId = ((Number) result).intValue();
            log.info("Odoo createSaleOrder success in {}ms — odooId={}", ms, odooId);

            // Auto-confirm to trigger stock transfer
            try {
                List<Object> confirmArgs = List.of(
                        odooDb, odooUid, odooPassword,
                        "sale.order", "action_confirm",
                        List.of(List.of(odooId))
                );
                Map<String, Object> confirmResponse = callRpc(confirmArgs);
                if (confirmResponse == null || confirmResponse.containsKey("error")) {
                    log.warn("Sale order confirmation failed for odooId={} — order kept as quotation: {}",
                            odooId, confirmResponse);
                } else {
                    log.info("Sale order confirmed in Odoo, transfer created — odooId={}", odooId);
                }
            } catch (Exception ce) {
                log.warn("Sale order confirmation exception for odooId={} — order kept as quotation: {}",
                        odooId, ce.getMessage());
            }

            return odooId;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo createSaleOrder exception in {}ms: {}", ms, e.getMessage(), e);
            return null;
        }
    }

    // ── Update res.partner address ────────────────────────────────────────────

    public boolean updatePartnerAddress(Integer partnerId, String street, String city) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> vals = new HashMap<>();
            if (street != null) vals.put("street", street);
            if (city   != null) vals.put("city",   city);
            if (vals.isEmpty()) return true;

            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "res.partner", "write",
                    List.of(List.of(partnerId), vals)
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.error("Odoo updatePartnerAddress failed in {}ms — partnerId={} response: {}",
                        ms, partnerId, response);
                return false;
            }

            log.info("Odoo updatePartnerAddress success in {}ms — partnerId={} street={} city={}",
                    ms, partnerId, street, city);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo updatePartnerAddress exception in {}ms — partnerId={}: {}",
                    ms, partnerId, e.getMessage(), e);
            return false;
        }
    }

    // ── Cancel sale.order ─────────────────────────────────────────────────────

    public boolean cancelSaleOrder(Integer erpOrderId) {
        long start = System.currentTimeMillis();
        try {
            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order", "action_cancel",
                    List.of(List.of(erpOrderId))
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.error("Odoo cancelSaleOrder failed in {}ms — erpOrderId={} response: {}",
                        ms, erpOrderId, response);
                return false;
            }

            log.info("Odoo cancelSaleOrder success in {}ms — erpOrderId={}", ms, erpOrderId);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo cancelSaleOrder exception in {}ms — erpOrderId={}: {}",
                    ms, erpOrderId, e.getMessage(), e);
            return false;
        }
    }

    // ── Confirm sale.order (triggers stock move) ──────────────────────────────

    public boolean updateStock(Integer erpOrderId) {
        long start = System.currentTimeMillis();
        try {
            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order", "action_confirm",
                    List.of(List.of(erpOrderId))
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.error("Odoo updateStock failed in {}ms — erpOrderId={} response: {}",
                        ms, erpOrderId, response);
                return false;
            }

            log.info("Odoo updateStock success in {}ms — erpOrderId={}", ms, erpOrderId);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo updateStock exception in {}ms — erpOrderId={}: {}",
                    ms, erpOrderId, e.getMessage(), e);
            return false;
        }
    }

    // ── Validate stock.picking transfer linked to sale.order ─────────────────

    @SuppressWarnings("unchecked")
    public boolean validateTransfer(Integer erpOrderId) {
        long start = System.currentTimeMillis();
        try {
            // Find picking linked to the sale order
            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("fields", List.of("id", "state"));
            kwargs.put("limit", 1);

            List<Object> searchArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.picking", "search_read",
                    List.of(List.of(List.of("sale_id", "=", erpOrderId))),
                    kwargs
            );

            Map<String, Object> searchResponse = callRpc(searchArgs);
            long ms = System.currentTimeMillis() - start;

            if (searchResponse == null || searchResponse.containsKey("error")) {
                log.warn("Odoo validateTransfer: picking search failed in {}ms — erpOrderId={} response: {}",
                        ms, erpOrderId, searchResponse);
                return false;
            }

            List<Map<String, Object>> pickings = (List<Map<String, Object>>) searchResponse.get("result");
            if (pickings == null || pickings.isEmpty()) {
                log.warn("Odoo validateTransfer: no picking found for erpOrderId={}", erpOrderId);
                return false;
            }

            Map<String, Object> picking = pickings.get(0);
            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            log.info("Odoo validateTransfer: found pickingId={} state={} for erpOrderId={}", pickingId, state, erpOrderId);

            if ("done".equals(state)) {
                log.info("Odoo validateTransfer: picking already done — erpOrderId={}", erpOrderId);
                return true;
            }

            // Step 1: get all move lines for this picking
            // Odoo 16 renamed: product_uom_qty → reserved_uom_qty, qty_done → quantity
            Map<String, Object> mlKwargs = new HashMap<>();
            mlKwargs.put("fields", List.of("id", "reserved_uom_qty", "qty_done"));

            List<Object> mlSearchArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.move.line", "search_read",
                    List.of(List.of(List.of("picking_id", "=", pickingId))),
                    mlKwargs
            );
            Map<String, Object> mlResponse = callRpc(mlSearchArgs);
            if (mlResponse == null || mlResponse.containsKey("error")) {
                log.warn("Odoo validateTransfer: move line search failed — pickingId={} response: {}", pickingId, mlResponse);
            } else {
                List<Map<String, Object>> moveLines = (List<Map<String, Object>>) mlResponse.get("result");
                if (moveLines != null && !moveLines.isEmpty()) {
                    // Step 2: set quantity (done qty) = reserved_uom_qty on each move line
                    for (Map<String, Object> ml : moveLines) {
                        Integer mlId = ((Number) ml.get("id")).intValue();
                        Object reserved = ml.get("reserved_uom_qty");
                        if (reserved != null) {
                            Map<String, Object> qtyVals = new HashMap<>();
                            qtyVals.put("qty_done", reserved);
                            List<Object> writeQtyArgs = List.of(
                                    odooDb, odooUid, odooPassword,
                                    "stock.move.line", "write",
                                    List.of(List.of(mlId), qtyVals)
                            );
                            Map<String, Object> writeRes = callRpc(writeQtyArgs);
                            if (writeRes != null && writeRes.containsKey("error")) {
                                log.warn("Odoo validateTransfer: write qty failed for mlId={}: {}", mlId, writeRes);
                            }
                        }
                    }
                    log.info("Odoo validateTransfer: set quantity on {} move lines for pickingId={}", moveLines.size(), pickingId);
                } else {
                    log.warn("Odoo validateTransfer: no move lines found for pickingId={}", pickingId);
                }
            }

            // Step 3: button_validate — should now return True with qty_done set
            List<Object> validateArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.picking", "button_validate",
                    List.of(List.of(pickingId))
            );

            Map<String, Object> validateResponse = callRpc(validateArgs);
            long totalMs = System.currentTimeMillis() - start;

            if (validateResponse == null || validateResponse.containsKey("error")) {
                log.warn("Odoo validateTransfer: button_validate failed in {}ms — pickingId={} response: {}",
                        totalMs, pickingId, validateResponse);
                return false;
            }

            Object result = validateResponse.get("result");
            if (result instanceof Map) {
                log.warn("Odoo validateTransfer: button_validate still returned action dict in {}ms — pickingId={} resModel={}",
                        totalMs, pickingId, ((Map<?,?>) result).get("res_model"));
                return false;
            }

            log.info("Odoo validateTransfer: success in {}ms — pickingId={} erpOrderId={}", totalMs, pickingId, erpOrderId);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo validateTransfer exception in {}ms — erpOrderId={}: {}", ms, erpOrderId, e.getMessage(), e);
            return false;
        }
    }

    // ── Create invoice from sale.order via wizard (Odoo 16/17) ──────────────────
    // Uses sale.advance.payment.inv wizard since _create_invoices is private RPC

    @SuppressWarnings("unchecked")
    public Integer createInvoice(Integer erpOrderId) {
        long start = System.currentTimeMillis();
        try {
            // Step 1: create the invoicing wizard
            Map<String, Object> wizardVals = new HashMap<>();
            wizardVals.put("advance_payment_method", "delivered");
            wizardVals.put("sale_order_ids", List.of(List.of(6, 0, List.of(erpOrderId))));

            List<Object> createWizardArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.advance.payment.inv", "create",
                    List.of(wizardVals)
            );

            Map<String, Object> wizardResponse = callRpc(createWizardArgs);
            if (wizardResponse == null || wizardResponse.containsKey("error")) {
                log.warn("Odoo createInvoice: wizard create failed — erpOrderId={} response: {}", erpOrderId, wizardResponse);
                return null;
            }

            Integer wizardId = ((Number) wizardResponse.get("result")).intValue();
            log.info("Odoo createInvoice: wizard created wizardId={} for erpOrderId={}", wizardId, erpOrderId);

            // Step 2: trigger invoice creation via wizard
            List<Object> invoiceArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.advance.payment.inv", "create_invoices",
                    List.of(List.of(wizardId))
            );

            Map<String, Object> invoiceResponse = callRpc(invoiceArgs);
            if (invoiceResponse == null || invoiceResponse.containsKey("error")) {
                log.warn("Odoo createInvoice: create_invoices failed — erpOrderId={} response: {}", erpOrderId, invoiceResponse);
                return null;
            }

            // Step 3: find the created invoice linked to the sale order
            Map<String, Object> searchKwargs = new HashMap<>();
            searchKwargs.put("fields", List.of("id", "state", "name"));
            searchKwargs.put("limit", 1);

            List<Object> searchArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "account.move", "search_read",
                    List.of(List.of(
                            List.of("invoice_line_ids.sale_line_ids.order_id", "=", erpOrderId),
                            List.of("move_type", "=", "out_invoice")
                    )),
                    searchKwargs
            );

            Map<String, Object> searchResponse = callRpc(searchArgs);
            long ms = System.currentTimeMillis() - start;

            if (searchResponse == null || searchResponse.containsKey("error")) {
                log.warn("Odoo createInvoice: invoice search failed in {}ms — erpOrderId={}", ms, erpOrderId);
                return null;
            }

            List<Map<String, Object>> invoices = (List<Map<String, Object>>) searchResponse.get("result");
            if (invoices == null || invoices.isEmpty()) {
                log.warn("Odoo createInvoice: no invoice found after creation in {}ms — erpOrderId={}", ms, erpOrderId);
                return null;
            }

            Integer invoiceId = ((Number) invoices.get(0).get("id")).intValue();
            String invoiceState = (String) invoices.get(0).get("state");
            log.info("Odoo createInvoice success in {}ms — erpOrderId={} invoiceId={} state={}", ms, erpOrderId, invoiceId, invoiceState);
            return invoiceId;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo createInvoice exception in {}ms — erpOrderId={}: {}", ms, erpOrderId, e.getMessage(), e);
            return null;
        }
    }

    // ── Post (confirm) invoice — registers payment for PREPAID ───────────────

    public boolean registerPayment(Integer invoiceId) {
        long start = System.currentTimeMillis();
        try {
            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "account.move", "action_post",
                    List.of(List.of(invoiceId))
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.warn("Odoo registerPayment failed in {}ms — invoiceId={} response: {}", ms, invoiceId, response);
                return false;
            }

            log.info("Odoo registerPayment success in {}ms — invoiceId={}", ms, invoiceId);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo registerPayment exception in {}ms — invoiceId={}: {}", ms, invoiceId, e.getMessage(), e);
            return false;
        }
    }

    // ── Internal: build and execute JSON-RPC call ─────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callRpc(List<Object> executeKwArgs) {
        Map<String, Object> params = new HashMap<>();
        params.put("service", "object");
        params.put("method", "execute_kw");
        params.put("args", executeKwArgs);

        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", "call");
        body.put("params", params);

        return restTemplate.postForObject(odooUrl, body, Map.class);
    }
}
