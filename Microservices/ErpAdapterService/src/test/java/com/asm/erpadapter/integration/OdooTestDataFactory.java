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

        // How a product is declared stockable changed after Odoo 16: `type` lost the "product"
        // value and the meaning moved to `is_storable`, with type staying "consu". Sending the old
        // value to a recent Odoo is rejected outright, so the fixture has to know which it is
        // talking to — the very version difference these tests exist to cover.
        Map<String, Object> vals = new java.util.HashMap<>(Map.of(
                "name", name,
                "barcode", barcode,
                "list_price", listPrice
        ));
        if (odoo.getVersion() == OdooVersion.V16) {
            vals.put("type", "product");
        } else {
            vals.put("type", "consu");
            vals.put("is_storable", true);
        }
        Integer id = create("product.product", vals);
        stockUp(id, 1000);
        System.out.println("[TestDataFactory] Created product: " + name + " (id=" + id + ")");
        return id;
    }

    /**
     * Put inventory on hand for a product, in the first internal location.
     *
     * <p>Without it a confirmed sale order produces a delivery order with no move lines at all —
     * nothing is reserved, so there is nothing to set a delivered quantity on, and validation is
     * refused for "a zero quantity transfer". The tests then look like the workflow is broken when
     * the warehouse is simply empty.
     */
    public void stockUp(int productId, double quantity) {
        List<Map<String, Object>> locations = searchRead("stock.location",
                List.of(List.of("usage", "=", "internal")), List.of("id"), 1);
        if (locations.isEmpty()) return;
        int locationId = ((Number) locations.get(0).get("id")).intValue();
        create("stock.quant", Map.of(
                "product_id", productId,
                "location_id", locationId,
                "quantity", quantity));
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
        // Via sale.order.picking_ids, not a domain on stock.move.sale_id — that field does not
        // exist on stock.move, and searchRead answers an invalid domain with an empty list, so the
        // lookup reported "no delivery" instead of "I asked the wrong question".
        List<Map<String, Object>> orders = searchRead("sale.order",
                List.of(List.of("id", "=", saleOrderId)), List.of("picking_ids"), 1);
        List<Map<String, Object>> moves = List.of();
        if (!orders.isEmpty() && orders.get(0).get("picking_ids") instanceof List<?> ids && !ids.isEmpty()) {
            moves = List.of(Map.of("picking_id", ids.get(0)));
        }
        if (moves.isEmpty()) return null;

        // Odoo returns a many2one as the pair [id, display_name], not an object. Testing for a Map
        // meant this returned null every single time, and every workflow test read that as "the sale
        // order produced no delivery" — a failure that looks like broken business logic and is not.
        Object pickingRef = moves.get(0).get("picking_id");
        Integer pickingId = null;
        if (pickingRef instanceof Number n) {          // one2many: a bare id
            pickingId = n.intValue();
        } else if (pickingRef instanceof List<?> pair && !pair.isEmpty() && pair.get(0) instanceof Number n) {
            pickingId = n.intValue();
        } else if (pickingRef instanceof Map<?, ?> rel && rel.get("id") instanceof Number n) {
            pickingId = n.intValue();
        }
        if (pickingId != null) {
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

    // ── Confirmation wizards ─────────────────────────────────────────────────

    /**
     * Carry a {@code button_validate} through to a settled transfer, whatever it asks for on the way.
     *
     * <p>Validation rarely finishes in one call. Odoo answers with an {@code ir.actions.act_window}
     * describing a confirmation the user is expected to give, and the transfer stays put until it is
     * given. Two details make that easy to get wrong, and both cost this suite a red job:
     *
     * <ul>
     *   <li><b>The wizard record does not exist yet.</b> On Odoo 16 <i>and</i> 19 the action comes back
     *       with no {@code res_id} — only {@code default_*} keys in its context. The web client builds
     *       the record from those; over RPC nobody does, so code that looks for {@code res_id} and gives
     *       up silently leaves the picking {@code assigned} and reports no error at all.</li>
     *   <li><b>Wizards chain.</b> On Odoo 16, processing "Immediate Transfer?" returns "Create
     *       Backorder?". Handling one and stopping is the same silent nothing.</li>
     * </ul>
     *
     * <p>So this follows the chain instead of assuming its length, and stops when Odoo stops asking.
     */
    public void settleValidationWizards(Object validateResult) {
        Object step = validateResult;
        // A confirmation that keeps asking is a bug in this loop, not a workflow; five is far past
        // anything Odoo does and turns a hang into a readable failure.
        for (int guard = 0; guard < 5; guard++) {
            if (!(step instanceof Map<?, ?> action)) return;
            Object model = action.get("res_model");
            if (!(model instanceof String wizardModel) || wizardModel.isEmpty()) return;

            Map<String, Object> context = action.get("context") instanceof Map<?, ?> c
                    ? castContext(c) : Map.of();

            int wizardId;
            if (action.get("res_id") instanceof Number n && n.intValue() > 0) {
                wizardId = n.intValue();
            } else {
                Map<String, Object> defaults = new java.util.HashMap<>();
                context.forEach((k, v) -> {
                    if (k.startsWith("default_")) defaults.put(k.substring("default_".length()), v);
                });
                if (defaults.isEmpty()) return;   // nothing to build the wizard from
                addWizardLines(wizardModel, defaults);
                wizardId = create(wizardModel, defaults, context);
            }
            System.out.println("[TestDataFactory] Settling wizard " + wizardModel + "#" + wizardId);
            step = callMethod(wizardModel, "process", List.of(wizardId), context);
        }
    }

    /**
     * Give a confirmation wizard the one line per picking it is really being asked about.
     *
     * <p>{@code pick_ids} alone builds a wizard that answers for nothing: {@code process()} walks the
     * per-picking lines, and with none it transfers zero and leaves the picking {@code assigned}
     * without complaint. On Odoo 16 that silence is compounded — the empty immediate transfer then
     * raises a backorder for the whole order, so the chain "succeeds" twice and delivers nothing.
     * The web client builds these lines in {@code default_get}; over RPC they have to be stated.
     */
    private static void addWizardLines(String wizardModel, Map<String, Object> vals) {
        String lineField;
        String flag;
        switch (wizardModel) {
            case "stock.immediate.transfer" -> { lineField = "immediate_transfer_line_ids"; flag = "to_immediate"; }
            case "stock.backorder.confirmation" -> { lineField = "backorder_confirmation_line_ids"; flag = "to_backorder"; }
            default -> { return; }
        }
        if (!(vals.get("pick_ids") instanceof List<?> commands)) return;

        List<Object> lines = new java.util.ArrayList<>();
        for (Object command : commands) {
            // x2many commands arrive as [4, id] — "link this existing record".
            if (command instanceof List<?> pair && pair.size() > 1 && pair.get(1) instanceof Number id) {
                lines.add(List.of(0, 0, Map.of("picking_id", id.intValue(), flag, true)));
            }
        }
        if (!lines.isEmpty()) vals.put(lineField, lines);
    }

    /** Validate a picking and see the confirmation through — the whole gesture, as a user makes it. */
    public void validatePicking(int pickingId) {
        settleValidationWizards(callMethod("stock.picking", "button_validate", List.of(pickingId)));
    }

    // ── Return wizard ────────────────────────────────────────────────────────

    /**
     * Build a return wizard for a delivered picking, with lines that are actually returnable.
     *
     * <p>{@code stock.return.picking} fills its lines in {@code default_get}, which the web client
     * triggers and a bare {@code create()} over RPC does not — on Odoo 16 the wizard comes back empty
     * and the return is refused for "at least one non-zero quantity", which reads like a broken
     * workflow and is only a missing prefill. Odoo 19 computes the lines but leaves them at zero.
     *
     * <p>Both are handled the way {@code OdooProductService} handles them in production: materialise
     * the lines from the source picking's moves when they are missing, then set the quantity.
     */
    public int createReturnWizard(int pickingId, double quantity) {
        Map<String, Object> context = Map.of(
                "active_id", pickingId,
                "active_ids", List.of(pickingId),
                "active_model", "stock.picking");
        int wizardId = create("stock.return.picking", Map.of("picking_id", pickingId), context);

        List<Map<String, Object>> lines = searchRead("stock.return.picking.line",
                List.of(List.of("wizard_id", "=", wizardId)), List.of("id"), 50);
        if (lines.isEmpty()) {
            for (Map<String, Object> move : searchRead("stock.move",
                    List.of(List.of("picking_id", "=", pickingId)), List.of("id", "product_id"), 50)) {
                Object productRef = move.get("product_id");
                Integer productId = productRef instanceof List<?> pair && !pair.isEmpty()
                        && pair.get(0) instanceof Number n ? n.intValue() : null;
                if (productId == null) continue;
                create("stock.return.picking.line", Map.of(
                        "wizard_id", wizardId,
                        "product_id", productId,
                        "move_id", ((Number) move.get("id")).intValue(),
                        "quantity", quantity));
            }
        } else {
            for (Map<String, Object> line : lines) {
                write("stock.return.picking.line", ((Number) line.get("id")).intValue(),
                        Map.of("quantity", quantity));
            }
        }
        return wizardId;
    }

    /**
     * The method that creates the reverse transfer, under the name this Odoo knows it by.
     *
     * <p>Renamed in Odoo 18: {@code create_returns} → {@code action_create_returns}. Neither name
     * exists on both, so calling the wrong one fails with "method does not exist" — which is exactly
     * the breakage these two-version tests exist to catch, and the reason the name is derived from the
     * version rather than tried in turn.
     */
    public String returnMethod() {
        return odoo.getVersion() == OdooVersion.V16 ? "create_returns" : "action_create_returns";
    }

    // ── Generic Odoo operations ──────────────────────────────────────────────

    /**
     * Create a record in Odoo.
     */
    public Integer create(String model, Map<String, Object> vals) {
        return create(model, vals, null);
    }

    /**
     * Create a record, with a context — wizards need one to prefill themselves from the active record.
     */
    public Integer create(String model, Map<String, Object> vals, Map<String, Object> context) {
        Object uid = authenticate();
        // `vals` goes inside the positional list: passing it bare makes Odoo read it as a *list of*
        // records to create, which succeeds and answers with a list of ids instead of an id — a
        // ClassCastException three calls later, nowhere near the mistake.
        Map<String, Object> params = new java.util.HashMap<>(Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", context == null
                        ? List.of(odoo.getDb(), uid, odoo.getPassword(), model, "create", List.of(vals))
                        : List.of(odoo.getDb(), uid, odoo.getPassword(), model, "create", List.of(vals),
                                  Map.of("context", context))
        ));
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
    public Object callMethod(String model, String method, List<Object> args) {
        return callMethod(model, method, args, null);
    }

    /**
     * Call a method with a context. Wizards read {@code active_id} and the {@code button_validate_*}
     * keys from it, and refuse or misbehave without them.
     */
    public Object callMethod(String model, String method, List<Object> args, Map<String, Object> context) {
        Object uid = authenticate();
        Map<String, Object> params = Map.of(
                "service", "object",
                "method", "execute_kw",
                "args", context == null || context.isEmpty()
                        ? List.of(odoo.getDb(), uid, odoo.getPassword(), model, method, List.of(args))
                        : List.of(odoo.getDb(), uid, odoo.getPassword(), model, method, List.of(args),
                                  Map.of("context", context))
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castContext(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

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
