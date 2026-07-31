package com.asm.erpadapter.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates test data in Odoo via JSON-RPC. Used by integration tests to set up
 * sale orders, products, pickings, and quantities in isolation.
 *
 * <p>Each method is idempotent — calling it twice with the same data won't create duplicates.
 */
public class OdooTestDataFactory {

    private final OdooContainer odoo;
    private final HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private int cachedUid = -1;

    public OdooTestDataFactory(OdooContainer odoo) {
        this.odoo = odoo;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    // ── Product ──────────────────────────────────────────────────────────────

    /**
     * Create a product in Odoo. Returns the product ID.
     */
    public int createProduct(String name, String barcode, double listPrice) {
        List<Map<String, Object>> existing = searchRead("product.product",
                List.of(List.of("barcode", "=", barcode)), List.of("id"), 1);
        if (!existing.isEmpty()) {
            return ((Number) existing.get(0).get("id")).intValue();
        }

        Map<String, Object> vals = Map.of(
                "name", name,
                "barcode", barcode,
                "list_price", listPrice,
                "type", "product"
        );
        Integer id = create("product.product", vals);
        System.out.println("[TestDataFactory] Created product: " + name + " (id=" + id + ")");
        return id;
    }

    // ── Sale Order ───────────────────────────────────────────────────────────

    /**
     * Create a sale order in Odoo. Returns the sale order ID.
     */
    public int createSaleOrder(int partnerId, List<OrderLine> lines) {
        Map<String, Object> vals = new java.util.HashMap<>();
        vals.put("partner_id", partnerId);
        vals.put("order_line", lines.stream()
                .map(line -> List.of(0, 0, Map.of(
                        "product_id", line.productId(),
                        "product_uom_qty", line.qty(),
                        "price_unit", line.price())))
                .toList());

        Integer id = create("sale.order", vals);
        System.out.println("[TestDataFactory] Created sale order: id=" + id);
        return id;
    }

    /**
     * Confirm a sale order.
     */
    public void confirmSaleOrder(int saleOrderId) {
        callMethod("sale.order", "action_confirm", List.of(saleOrderId));
        System.out.println("[TestDataFactory] Confirmed sale order: id=" + saleOrderId);
    }

    // ── Picking ──────────────────────────────────────────────────────────────

    /**
     * Find the picking associated with a sale order.
     */
    public Map<String, Object> findPickingBySaleOrder(int saleOrderId) {
        List<Map<String, Object>> moves = searchRead("stock.move",
                List.of(List.of("sale_id", "=", saleOrderId)),
                List.of("picking_id"), 100);
        if (moves.isEmpty()) return null;

        Object pickingRef = moves.get(0).get("picking_id");
        if (pickingRef instanceof Map<?, ?> rel) {
            Integer pickingId = ((Number) rel.get("id")).intValue();
            List<Map<String, Object>> picks = searchRead("stock.picking",
                    List.of(List.of("id", "=", pickingId)),
                    List.of("id", "state", "name"), 1);
            return picks.isEmpty() ? null : picks.get(0);
        }
        return null;
    }

    // ── Quantity manipulation ────────────────────────────────────────────────

    /**
     * Set qty_done on a picking's move lines. This simulates the driver delivering
     * specific quantities.
     */
    public void setQuantityDone(int pickingId, int productId, double qtyDone) {
        List<Map<String, Object>> moveLines = searchRead("stock.move.line",
                List.of(List.of("picking_id", "=", pickingId),
                        List.of("product_id", "=", productId)),
                List.of("id"), 10);

        for (Map<String, Object> ml : moveLines) {
            int mlId = ((Number) ml.get("id")).intValue();
            write("stock.move.line", mlId, Map.of("qty_done", qtyDone));
        }
        System.out.println("[TestDataFactory] Set qty_done=" + qtyDone
                + " for product=" + productId + " in picking=" + pickingId);
    }

    /**
     * Set qty_done on a picking's move lines using the quantity field (Odoo 17+).
     * Falls back to qty_done if the field doesn't exist.
     */
    public void setQuantityField(int pickingId, int productId, double qtyDone, String fieldName) {
        List<Map<String, Object>> moveLines = searchRead("stock.move.line",
                List.of(List.of("picking_id", "=", pickingId),
                        List.of("product_id", "=", productId)),
                List.of("id"), 10);

        for (Map<String, Object> ml : moveLines) {
            int mlId = ((Number) ml.get("id")).intValue();
            write("stock.move.line", mlId, Map.of(fieldName, qtyDone));
        }
        System.out.println("[TestDataFactory] Set " + fieldName + "=" + qtyDone
                + " for product=" + productId + " in picking=" + pickingId);
    }

    // ── Generic Odoo operations ──────────────────────────────────────────────

    /**
     * Create a record in Odoo.
     */
    @SuppressWarnings("unchecked")
    public Integer create(String model, Map<String, Object> vals) {
        Object uid = authenticate();
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", List.of(odoo.getDb(), uid, odoo.getPassword(), model, "create", List.of(vals))
        );
        Map<String, Object> resp = jsonRpc(params);
        Object result = resp.get("result");
        if (result instanceof Number n) return n.intValue();
        throw new RuntimeException("create failed for model=" + model + ", resp=" + resp);
    }

    /**
     * Write to a record in Odoo.
     */
    public void write(String model, int id, Map<String, Object> vals) {
        Object uid = authenticate();
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", List.of(odoo.getDb(), uid, odoo.getPassword(), model, "write",
                        List.of(List.of(id), vals))
        );
        jsonRpc(params);
    }

    /**
     * Search and read records from Odoo.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchRead(String model, List<List<Object>> domain,
                                                 List<String> fields, int limit) {
        Object uid = authenticate();
        Map<String, Object> kwargs = Map.of("fields", fields, "limit", limit);
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", List.of(odoo.getDb(), uid, odoo.getPassword(), model, "search_read",
                        List.of(domain), kwargs)
        );
        Map<String, Object> resp = jsonRpc(params);
        Object result = resp.get("result");
        if (result instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    /**
     * Call a method on an Odoo model.
     */
    @SuppressWarnings("unchecked")
    public Object callMethod(String model, String method, List<Object> args) {
        Object uid = authenticate();
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", List.of(odoo.getDb(), uid, odoo.getPassword(), model, method, List.of(args))
        );
        Map<String, Object> resp = jsonRpc(params);
        // JSON-RPC reports failure in the body, not the transport, so a refused call arrives as a
        // 200 carrying an `error` object. Returning it as a null result made every failure look
        // like a method that quietly did nothing — which is how a missing method on a newer Odoo
        // would have passed unnoticed, the exact thing these tests exist to catch.
        if (resp.get("error") != null) {
            throw new RuntimeException("Odoo refused " + model + "." + method + ": " + resp.get("error"));
        }
        return resp.get("result");
    }

    /**
     * Read fields from a record.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(String model, int id, List<String> fields) {
        Object uid = authenticate();
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                // `fields` belongs in the keyword arguments, the way searchRead already passes it.
                // Positionally it becomes a list of field names one of which is literally "fields",
                // and Odoo answers with a record carrying none of what was asked for.
                "args", List.of(odoo.getDb(), uid, odoo.getPassword(), model, "read",
                        List.of(List.of(id)), Map.of("fields", fields))
        );
        Map<String, Object> resp = jsonRpc(params);
        Object result = resp.get("result");
        if (result instanceof List<?> list && !list.isEmpty()) {
            return (Map<String, Object>) list.get(0);
        }
        return Map.of();
    }

    /**
     * Check if a field exists on a model via fields_get.
     */
    public boolean fieldExists(String model, String fieldName) {
        Object uid = authenticate();
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", List.of(odoo.getDb(), uid, odoo.getPassword(), model, "fields_get",
                        List.of(List.of(fieldName)))
        );
        Map<String, Object> resp = jsonRpc(params);
        Object result = resp.get("result");
        if (result instanceof Map<?, ?> fields) {
            return fields.containsKey(fieldName);
        }
        return false;
    }

    /**
     * Get all field names for a model via fields_get.
     */
    @SuppressWarnings("unchecked")
    public Set<String> getFieldNames(String model) {
        Object uid = authenticate();
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", List.of(odoo.getDb(), uid, odoo.getPassword(), model, "fields_get",
                        List.of())
        );
        Map<String, Object> resp = jsonRpc(params);
        Object result = resp.get("result");
        if (result instanceof Map<?, ?> fields) {
            Set<String> names = new HashSet<>();
            for (Object key : fields.keySet()) {
                names.add(String.valueOf(key));
            }
            return names;
        }
        return Set.of();
    }

    // ── Partner (for sale orders) ────────────────────────────────────────────

    /**
     * Create a partner (customer) in Odoo.
     */
    public int createPartner(String name) {
        Integer id = create("res.partner", Map.of("name", name));
        System.out.println("[TestDataFactory] Created partner: " + name + " (id=" + id + ")");
        return id;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private int authenticate() {
        if (cachedUid > 0) return cachedUid;
        cachedUid = odoo.authenticate();
        return cachedUid;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonRpc(Map<String, Object> params) {
        try {
            Map<String, Object> body = Map.of(
                    "jsonrpc", "2.0",
                    "method", "call",
                    "params", params
            );
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(odoo.getOdooUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readValue(response.body(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Odoo JSON-RPC call failed: " + e.getMessage(), e);
        }
    }

    // ── Record types ─────────────────────────────────────────────────────────

    public record OrderLine(int productId, double qty, double price) {}
}
