package com.asm.delivery.odoo;

import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.dto.request.PartialDeliveryItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
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
            log.warn("Odoo searchProductByName failed for name={}", name, e);
            return null;
        }
    }

    // ── Search partner by phone/mobile ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Integer searchPartnerByPhone(String phone) {
        try {
            if (phone == null || phone.isBlank()) return null;

            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("fields", List.of("id", "name", "phone"));
            kwargs.put("limit", 1);

            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "res.partner", "search_read",
                    List.of(List.of("|", List.of("phone", "=", phone), List.of("mobile", "=", phone))),
                    kwargs
            );

            Map<String, Object> response = callRpc(args);
            if (response == null || response.containsKey("error")) return null;

            List<Map<String, Object>> partners = (List<Map<String, Object>>) response.get("result");
            if (partners == null || partners.isEmpty()) return null;

            Object id = partners.get(0).get("id");
            if (id instanceof Number n) {
                return n.intValue();
            }
            return null;
        } catch (Exception e) {
            log.warn("Odoo searchPartnerByPhone failed for phone={}", phone, e);
            return null;
        }
    }

    // ── Create res.partner in Odoo ───────────────────────────────────────────

    public Integer createPartner(String name, String phone) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> partnerVals = new HashMap<>();
            partnerVals.put("name", name != null && !name.isBlank() ? name : phone);
            partnerVals.put("phone", phone);
            partnerVals.put("mobile", phone);
            partnerVals.put("customer_rank", 1);
            partnerVals.put("comment", "ASM Delivery client");

            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "res.partner", "create",
                    List.of(partnerVals)
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.warn("Odoo createPartner failed in {}ms — response: {}", ms, response);
                return null;
            }

            Object result = response.get("result");
            if (!(result instanceof Number n)) {
                log.warn("Odoo createPartner returned invalid result in {}ms: {}", ms, result);
                return null;
            }

            Integer partnerId = n.intValue();
            log.info("Odoo createPartner success in {}ms — partnerId={}", ms, partnerId);
            return partnerId;
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.warn("Odoo createPartner exception in {}ms", ms, e);
            return null;
        }
    }

    // ── Generic search_read helper ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchRead(
            String model,
            List<Object> domain,
            List<String> fields,
            int limit,
            String order
    ) {
        try {
            Map<String, Object> searchKwargs = new HashMap<>();
            if (limit > 0) {
                searchKwargs.put("limit", limit);
            }
            if (order != null && !order.isBlank()) {
                searchKwargs.put("order", order);
            }

            List<Object> searchArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    model, "search",
                    List.of(domain != null ? domain : List.of()),
                    searchKwargs
            );

            Map<String, Object> searchResponse = callRpc(searchArgs);
            if (searchResponse == null || searchResponse.containsKey("error")) {
                log.warn("Odoo search failed for model={}: {}", model, searchResponse);
                return List.of();
            }

            Object searchResult = searchResponse.get("result");
            if (!(searchResult instanceof List<?> rawIds)) {
                return List.of();
            }

            List<Integer> ids = new ArrayList<>();
            for (Object rawId : rawIds) {
                if (rawId instanceof Number n) {
                    ids.add(n.intValue());
                }
            }

            if (ids.isEmpty()) {
                return List.of();
            }

            Map<String, Object> readKwargs = new HashMap<>();
            if (fields != null && !fields.isEmpty()) {
                readKwargs.put("fields", fields);
            }

            List<Object> readArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    model, "read",
                    List.of(ids),
                    readKwargs
            );

            Map<String, Object> readResponse = callRpc(readArgs);
            if (readResponse == null || readResponse.containsKey("error")) {
                log.warn("Odoo read failed for model={}: {}", model, readResponse);
                return List.of();
            }

            Object readResult = readResponse.get("result");
            if (readResult instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Odoo searchRead failed for model={}", model, e);
            return List.of();
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
                log.warn("Sale order confirmation exception for odooId={} — order kept as quotation", odooId, ce);
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

    public boolean updatePartnerMapFields(Integer partnerId,
                                          String street,
                                          String street2,
                                          String city,
                                          String zip,
                                          Double latitude,
                                          Double longitude) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> vals = new HashMap<>();
            if (street != null && !street.isBlank()) vals.put("street", street);
            if (street2 != null && !street2.isBlank()) vals.put("street2", street2);
            if (city != null && !city.isBlank()) vals.put("city", city);
            if (zip != null && !zip.isBlank()) vals.put("zip", zip);

            if (!vals.isEmpty()) {
                List<Object> args = List.of(
                        odooDb, odooUid, odooPassword,
                        "res.partner", "write",
                        List.of(List.of(partnerId), vals)
                );
                Map<String, Object> response = callRpc(args);
                if (response == null || response.containsKey("error")) {
                    log.warn("Odoo updatePartnerMapFields address write failed partnerId={} response={}", partnerId, response);
                    return false;
                }
            }

            if (latitude != null && longitude != null) {
                Map<String, Object> geoVals = new HashMap<>();
                geoVals.put("partner_latitude", latitude);
                geoVals.put("partner_longitude", longitude);

                List<Object> geoArgs = List.of(
                        odooDb, odooUid, odooPassword,
                        "res.partner", "write",
                        List.of(List.of(partnerId), geoVals)
                );
                Map<String, Object> geoResponse = callRpc(geoArgs);
                if (geoResponse == null || geoResponse.containsKey("error")) {
                    log.warn("Odoo updatePartnerMapFields geo write skipped partnerId={} response={}", partnerId, geoResponse);
                }
            }

            log.info("Odoo updatePartnerMapFields success in {}ms partnerId={}", System.currentTimeMillis() - start, partnerId);
            return true;
        } catch (Exception e) {
            log.warn("Odoo updatePartnerMapFields exception partnerId={}", partnerId, e);
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

            Object result = response.get("result");
            if (result instanceof Map<?, ?> action) {
                boolean handled = handleSaleCancelAction(action, erpOrderId);
                if (!handled) {
                    log.warn("Odoo cancelSaleOrder: could not process cancel action wizard — erpOrderId={} action={}",
                            erpOrderId, action);
                    return false;
                }
            }

            String state = readSaleOrderState(erpOrderId);
            if (!"cancel".equalsIgnoreCase(state)) {
                log.warn("Odoo cancelSaleOrder: call finished but state is '{}' (expected cancel) — erpOrderId={}",
                        state, erpOrderId);
                return false;
            }

            log.info("Odoo cancelSaleOrder success in {}ms — erpOrderId={}", ms, erpOrderId);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo cancelSaleOrder exception in {}ms — erpOrderId={}", ms, erpOrderId, e);
            return false;
        }
    }

    // ── Add note to sale.order ───────────────────────────────────────────────

    public boolean addNoteToSaleOrder(Integer erpOrderId, String note) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> vals = new HashMap<>();
            vals.put("note", note != null ? note : "");

            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order", "write",
                    List.of(List.of(erpOrderId), vals)
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.warn("Odoo addNoteToSaleOrder failed in {}ms — erpOrderId={} response: {}",
                        ms, erpOrderId, response);
                return false;
            }

                // Also post in chatter so the note is visible in messages/audit trails.
                Map<String, Object> chatterVals = new HashMap<>();
                chatterVals.put("body", note != null ? note : "");
                chatterVals.put("message_type", "comment");
                chatterVals.put("subtype_xmlid", "mail.mt_note");

                List<Object> chatterArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order", "message_post",
                    List.of(List.of(erpOrderId)),
                    chatterVals
                );
                Map<String, Object> chatterResponse = callRpc(chatterArgs);
                if (chatterResponse == null || chatterResponse.containsKey("error")) {
                log.warn("Odoo addNoteToSaleOrder: chatter post failed — erpOrderId={} response: {}",
                    erpOrderId, chatterResponse);
                }

            log.info("Odoo addNoteToSaleOrder success in {}ms — erpOrderId={}", ms, erpOrderId);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo addNoteToSaleOrder exception in {}ms — erpOrderId={}", ms, erpOrderId, e);
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
            log.error("Odoo updateStock exception in {}ms — erpOrderId={}", ms, erpOrderId, e);
            return false;
        }
    }

    // ── Validate stock.picking transfer linked to sale.order ─────────────────

    public boolean validateTransfer(Integer erpOrderId) {
        return validateTransfer(erpOrderId, null);
    }

    /**
     * Validates a transfer for a sale order. If {@code explicitPickingId} is provided (e.g. a
     * backorder picking from a previous partial delivery), that picking is used directly instead of
     * searching by sale-order link. This ensures the second delivery targets the correct backorder
     * picking rather than the already-done original picking.
     */
    @SuppressWarnings("unchecked")
    public boolean validateTransfer(Integer erpOrderId, Integer explicitPickingId) {
        long start = System.currentTimeMillis();
        try {
            // Ensure sale order is confirmed so Odoo can generate stock picking.
            updateStock(erpOrderId);

            Map<String, Object> picking;
            if (explicitPickingId != null) {
                picking = findPickingById(explicitPickingId);
                log.info("Odoo validateTransfer: using explicit backorder pickingId={} for erpOrderId={}", explicitPickingId, erpOrderId);
            } else {
                picking = findSinglePicking(erpOrderId);
            }
            if (picking == null) {
                log.warn("Odoo validateTransfer: no picking found for erpOrderId={} explicitPickingId={}", erpOrderId, explicitPickingId);
                return false;
            }

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            log.info("Odoo validateTransfer: found pickingId={} state={} for erpOrderId={}", pickingId, state, erpOrderId);

            if ("done".equals(state)) {
                log.info("Odoo validateTransfer: picking already done — erpOrderId={}", erpOrderId);
                return true;
            }

            // Reserve stock before writing done quantities.
            List<Object> assignArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.picking", "action_assign",
                    List.of(List.of(pickingId))
            );
            Map<String, Object> assignResponse = callRpc(assignArgs);
            if (assignResponse == null || assignResponse.containsKey("error")) {
                log.warn("Odoo validateTransfer: action_assign failed — pickingId={} response={}", pickingId, assignResponse);
            }

            // Step 1: set quantity_done on stock moves (robust across Odoo variants).
            Map<String, Object> mKwargs = new HashMap<>();
            mKwargs.put("fields", List.of("id", "product_uom_qty", "quantity_done"));

            List<Object> mSearchArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.move", "search_read",
                    List.of(List.of(List.of("picking_id", "=", pickingId))),
                    mKwargs
            );
            Map<String, Object> mResponse = callRpc(mSearchArgs);
            if (mResponse == null || mResponse.containsKey("error")) {
                log.warn("Odoo validateTransfer: stock.move search failed — pickingId={} response: {}", pickingId, mResponse);
            } else {
                List<Map<String, Object>> moves = (List<Map<String, Object>>) mResponse.get("result");
                if (moves != null && !moves.isEmpty()) {
                    for (Map<String, Object> move : moves) {
                        Integer moveId = ((Number) move.get("id")).intValue();
                        Object plannedQty = move.get("product_uom_qty");
                        if (!(plannedQty instanceof Number)) {
                            continue;
                        }

                        Map<String, Object> doneVals = new HashMap<>();
                        doneVals.put("quantity_done", plannedQty);

                        List<Object> writeDoneArgs = List.of(
                                odooDb, odooUid, odooPassword,
                                "stock.move", "write",
                                List.of(List.of(moveId), doneVals)
                        );
                        Map<String, Object> writeRes = callRpc(writeDoneArgs);
                        if (writeRes != null && writeRes.containsKey("error")) {
                            log.warn("Odoo validateTransfer: stock.move quantity write failed for moveId={}: {}", moveId, writeRes);
                        }
                    }
                    log.info("Odoo validateTransfer: set quantity_done on {} stock moves for pickingId={}", moves.size(), pickingId);
                } else {
                    log.warn("Odoo validateTransfer: no stock moves found for pickingId={}", pickingId);
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
            if (result instanceof Map<?, ?> action) {
                log.info("Odoo validateTransfer: processing action dict — pickingId={} resModel={}",
                        pickingId, action.get("res_model"));
                boolean handled = handleStockPickingAction(action, pickingId, true);
                if (!handled) {
                    log.warn("Odoo validateTransfer: unsupported action dict in {}ms — pickingId={} action={}",
                            totalMs, pickingId, action);
                    return false;
                }
            }

            String finalState = readPickingState(pickingId);
            if (!"done".equalsIgnoreCase(finalState)) {
                log.warn("Odoo validateTransfer: final picking state is '{}' (expected done) — pickingId={} erpOrderId={}",
                        finalState, pickingId, erpOrderId);
                return false;
            }

            log.info("Odoo validateTransfer: success in {}ms — pickingId={} erpOrderId={}", totalMs, pickingId, erpOrderId);
            return true;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo validateTransfer exception in {}ms — erpOrderId={}", ms, erpOrderId, e);
            return false;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer asRelId(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof List<?> rel && !rel.isEmpty() && rel.get(0) instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    // ── Resolve sale.order id from ERP reference (numeric id or name like S00004) ──

    @SuppressWarnings("unchecked")
    public Integer resolveSaleOrderId(String erpOrderRef) {
        if (erpOrderRef == null || erpOrderRef.isBlank()) {
            return null;
        }

        String normalized = erpOrderRef.trim();
        // Handle backorder suffixes (e.g. "1234-B1") to find the original sale string/id
        if (normalized.contains("-B")) {
            normalized = normalized.substring(0, normalized.indexOf("-B"));
        }

        // Fast path: already a numeric Odoo id.
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            // Continue with lookup by sale.order name/reference.
        }

        try {
            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("fields", List.of("id", "name", "state"));
            kwargs.put("limit", 1);

            // Exact name match first (e.g. S00004).
            List<Object> exactArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order", "search_read",
                    List.of(List.of(List.of("name", "=", normalized))),
                    kwargs
            );
            Map<String, Object> exactResponse = callRpc(exactArgs);
            if (exactResponse != null && !exactResponse.containsKey("error")) {
                List<Map<String, Object>> rows = (List<Map<String, Object>>) exactResponse.get("result");
                if (rows != null && !rows.isEmpty() && rows.get(0).get("id") instanceof Number n) {
                    Integer resolved = n.intValue();
                    log.info("Odoo resolveSaleOrderId exact match — ref={} -> id={}", normalized, resolved);
                    return resolved;
                }
            }

            // Fallback: case-insensitive like search (handles formatting differences).
            List<Object> likeArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order", "search_read",
                    List.of(List.of(List.of("name", "ilike", normalized))),
                    kwargs
            );
            Map<String, Object> likeResponse = callRpc(likeArgs);
            if (likeResponse != null && !likeResponse.containsKey("error")) {
                List<Map<String, Object>> rows = (List<Map<String, Object>>) likeResponse.get("result");
                if (rows != null && !rows.isEmpty() && rows.get(0).get("id") instanceof Number n) {
                    Integer resolved = n.intValue();
                    log.info("Odoo resolveSaleOrderId fallback match — ref={} -> id={}", normalized, resolved);
                    return resolved;
                }
            }

            log.warn("Odoo resolveSaleOrderId could not resolve ref={}", normalized);
            return null;
        } catch (Exception e) {
            log.warn("Odoo resolveSaleOrderId exception for ref={}", normalized, e);
            return null;
        }
    }

    // ── Validate partial stock.picking transfer linked to sale.order ───────────

    public record PartialTransferResult(boolean success, Integer pickingId, Integer backorderPickingId) {}

    @SuppressWarnings("unchecked")
    public PartialTransferResult validatePartialTransfer(Integer erpOrderId, List<com.asm.delivery.dto.request.PartialDeliveryItem> partialItems) {
        long start = System.currentTimeMillis();
        try {
            // Ensure sale order is confirmed so Odoo can generate stock picking.
            updateStock(erpOrderId);

            Map<String, Object> picking = findSinglePicking(erpOrderId);
            if (picking == null) {
                log.warn("Odoo validatePartialTransfer: no picking found for erpOrderId={}", erpOrderId);
                return new PartialTransferResult(false, null, null);
            }

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            log.info("Odoo validatePartialTransfer: found pickingId={} state={} for erpOrderId={}", pickingId, state, erpOrderId);

            if ("done".equals(state)) {
                log.info("Odoo validatePartialTransfer: picking already done — erpOrderId={}", erpOrderId);
                Integer existingBackorderId = findBackorderPickingId(pickingId);
                return new PartialTransferResult(true, pickingId, existingBackorderId);
            }

            // Reserve stock before writing partial done quantities.
            List<Object> assignArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.picking", "action_assign",
                    List.of(List.of(pickingId))
            );
            Map<String, Object> assignResponse = callRpc(assignArgs);
            if (assignResponse == null || assignResponse.containsKey("error")) {
                log.warn("Odoo validatePartialTransfer: action_assign failed — pickingId={} response={}", pickingId, assignResponse);
            }

            // Map item done quantities by product ID
            Map<Integer, Integer> productQtyDone = new HashMap<>();
            if (partialItems != null) {
                for (com.asm.delivery.dto.request.PartialDeliveryItem item : partialItems) {
                    String referenceKey = item != null ? item.referenceKey() : null;
                    Integer qtyDone = item != null ? item.getQuantityDone() : null;
                    if (qtyDone == null || qtyDone <= 0) {
                        log.warn("Odoo validatePartialTransfer: skipping partial item with invalid quantityDone={} referenceKey={}",
                                qtyDone, referenceKey);
                        continue;
                    }

                    Integer productId = resolveProductIdFromPartialSku(referenceKey);
                    if (productId != null) {
                        productQtyDone.merge(productId, qtyDone, Integer::sum);
                    }
                }
            }

            if (productQtyDone.isEmpty() && partialItems != null && !partialItems.isEmpty()) {
                log.error("Odoo validatePartialTransfer: no products resolved from partialItems for erpOrderId={} pickingId={} items={}",
                        erpOrderId, pickingId, partialItems.size());
                return new PartialTransferResult(false, pickingId, null);
            }

            boolean moveLinesUpdated = applyPartialQtyDoneToMoveLines(pickingId, productQtyDone);
            boolean movesUpdated = applyPartialQtyDoneToMoves(pickingId, productQtyDone);

            if (!moveLinesUpdated && !movesUpdated) {
                log.warn("Odoo validatePartialTransfer: no move lines updated for pickingId={}. Aborting validation to prevent UserError.", pickingId);
                return new PartialTransferResult(false, pickingId, null);
            }

            // Step 2: button_validate
            List<Object> validateArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.picking", "button_validate",
                    List.of(List.of(pickingId))
            );

            Map<String, Object> validateResponse = callRpc(validateArgs);
            long totalMs = System.currentTimeMillis() - start;

            if (validateResponse == null || validateResponse.containsKey("error")) {
                log.warn("Odoo validatePartialTransfer: button_validate failed in {}ms — pickingId={} response: {}",
                        totalMs, pickingId, validateResponse);
                return new PartialTransferResult(false, pickingId, null);
            }

            Object result = validateResponse.get("result");
            log.info("Odoo validatePartialTransfer: button_validate returned: {}", result);
            
            if (result instanceof Map<?, ?> action) {
                boolean handled = handleStockPickingAction(action, pickingId, false);
                if (!handled) {
                    log.warn("Odoo validatePartialTransfer: unsupported action dict — pickingId={} action={}", pickingId, action);
                    return new PartialTransferResult(false, pickingId, null);
                }
            }

            String finalState = readPickingState(pickingId);
            if (!"done".equalsIgnoreCase(finalState)) {
                log.warn("Odoo validatePartialTransfer: final picking state is '{}' (expected done) — pickingId={} erpOrderId={}",
                        finalState, pickingId, erpOrderId);
                return new PartialTransferResult(false, pickingId, null);
            }

            Integer backorderPickingId = findBackorderPickingId(pickingId);
            log.info("Odoo validatePartialTransfer: validated pickingId={} backorderId={} erpOrderId={}",
                    pickingId, backorderPickingId, erpOrderId);

            return new PartialTransferResult(true, pickingId, backorderPickingId);

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo validatePartialTransfer exception in {}ms — erpOrderId={}", ms, erpOrderId, e);
            return new PartialTransferResult(false, null, null);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean applyPartialQtyDoneToMoveLines(Integer pickingId, Map<Integer, Integer> productQtyDone) {
        Map<String, Object> mlKwargs = new HashMap<>();
        mlKwargs.put("fields", List.of("id", "product_id", "qty_done", "reserved_qty", "reserved_uom_qty"));

        List<Object> mlSearchArgs = List.of(
                odooDb, odooUid, odooPassword,
                "stock.move.line", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                mlKwargs
        );
        Map<String, Object> mlResponse = callRpc(mlSearchArgs);
        if (mlResponse == null || mlResponse.containsKey("error")) {
            log.warn("Odoo validatePartialTransfer: stock.move.line search failed — pickingId={} response={}", pickingId, mlResponse);
            return false;
        }

        List<Map<String, Object>> moveLines = (List<Map<String, Object>>) mlResponse.get("result");
        if (moveLines == null || moveLines.isEmpty()) {
            return false;
        }

        Map<Integer, Integer> remainingByProduct = new HashMap<>(productQtyDone);
        int updated = 0;

        for (Map<String, Object> line : moveLines) {
            Integer lineId = asInt(line.get("id"));
            Integer productId = asRelId(line.get("product_id"));
            if (lineId == null || productId == null) {
                continue;
            }

            int remaining = Math.max(remainingByProduct.getOrDefault(productId, 0), 0);
            Integer reservedQtyRaw = asInt(line.get("reserved_qty"));
            Integer reservedUomQtyRaw = asInt(line.get("reserved_uom_qty"));
            
            int lineReserved = reservedQtyRaw != null ? reservedQtyRaw : 0;
            if (lineReserved <= 0) {
                lineReserved = reservedUomQtyRaw != null ? reservedUomQtyRaw : 0;
            }
            if (lineReserved <= 0) {
                lineReserved = remaining;
            }

            int lineDone = Math.min(remaining, lineReserved);
            remainingByProduct.put(productId, Math.max(remaining - lineDone, 0));

            Map<String, Object> vals = new HashMap<>();
            vals.put("qty_done", lineDone);

            List<Object> writeArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.move.line", "write",
                    List.of(List.of(lineId), vals)
            );
            Map<String, Object> writeRes = callRpc(writeArgs);
            if (writeRes != null && writeRes.containsKey("error")) {
                log.warn("Odoo validatePartialTransfer: stock.move.line qty_done write failed lineId={} response={}", lineId, writeRes);
                continue;
            }
            updated++;
        }

        log.info("Odoo validatePartialTransfer: set qty_done on {} stock.move.line rows for pickingId={}", updated, pickingId);
        return updated > 0;
    }

    @SuppressWarnings("unchecked")
    private boolean applyPartialQtyDoneToMoves(Integer pickingId, Map<Integer, Integer> productQtyDone) {
        Map<String, Object> mKwargs = new HashMap<>();
        mKwargs.put("fields", List.of("id", "product_id", "product_uom_qty", "quantity_done"));

        List<Object> mSearchArgs = List.of(
                odooDb, odooUid, odooPassword,
                "stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                mKwargs
        );
        Map<String, Object> mResponse = callRpc(mSearchArgs);
        if (mResponse == null || mResponse.containsKey("error")) {
            log.warn("Odoo validatePartialTransfer: stock.move search failed — pickingId={} response: {}", pickingId, mResponse);
            return false;
        }

        List<Map<String, Object>> moves = (List<Map<String, Object>>) mResponse.get("result");
        if (moves == null || moves.isEmpty()) {
            return false;
        }

        int updatedCount = 0;

        for (Map<String, Object> move : moves) {
            Integer moveId = asInt(move.get("id"));
            Integer productId = asRelId(move.get("product_id"));
            if (moveId == null || productId == null) {
                continue;
            }

            int qtyDoneSetting = Math.max(productQtyDone.getOrDefault(productId, 0), 0);
            BigDecimal planned = asBigDecimal(move.get("product_uom_qty"));
            if (qtyDoneSetting > planned.intValue()) {
                qtyDoneSetting = planned.intValue();
            }

            Map<String, Object> doneVals = new HashMap<>();
            doneVals.put("quantity_done", qtyDoneSetting);

            List<Object> writeDoneArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.move", "write",
                    List.of(List.of(moveId), doneVals)
            );
            Map<String, Object> writeRes = callRpc(writeDoneArgs);
            if (writeRes != null && writeRes.containsKey("error")) {
                log.warn("Odoo validatePartialTransfer: stock.move quantity write failed for moveId={}: {}", moveId, writeRes);
            } else {
                updatedCount++;
            }
        }
        
        log.info("Odoo validatePartialTransfer: updated {} stock.move rows for pickingId={}", updatedCount, pickingId);
        return updatedCount > 0;
    }

    @SuppressWarnings("unchecked")
    public boolean syncSaleOrderLineDeliveredQuantities(Integer erpOrderId,
                                                        List<PartialDeliveryItem> partialItems,
                                                        boolean fullDelivery) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("fields", List.of("id", "product_id", "product_uom_qty", "qty_delivered", "display_type"));

            List<Object> searchArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order.line", "search_read",
                    List.of(List.of(
                            List.of("order_id", "=", erpOrderId),
                            List.of("display_type", "=", false)
                    )),
                    kwargs
            );

            Map<String, Object> response = callRpc(searchArgs);
            if (response == null || response.containsKey("error")) {
                log.warn("Odoo syncSaleOrderLineDeliveredQuantities: search_read failed erpOrderId={} response={}", erpOrderId, response);
                return false;
            }

            List<Map<String, Object>> lines = (List<Map<String, Object>>) response.get("result");
            if (lines == null || lines.isEmpty()) {
                log.warn("Odoo syncSaleOrderLineDeliveredQuantities: no order lines found erpOrderId={}", erpOrderId);
                return false;
            }

            Map<Integer, Integer> partialDoneByProduct = new HashMap<>();
            if (!fullDelivery && partialItems != null) {
                for (PartialDeliveryItem item : partialItems) {
                    Integer productId = resolveProductIdFromPartialSku(item != null ? item.referenceKey() : null);
                    if (productId != null) {
                        partialDoneByProduct.put(productId, item.getQuantityDone() != null ? Math.max(item.getQuantityDone(), 0) : 0);
                    }
                }
            }

            int updated = 0;
            for (Map<String, Object> line : lines) {
                Integer lineId = asInt(line.get("id"));
                Integer productId = asRelId(line.get("product_id"));
                if (lineId == null || productId == null) {
                    continue;
                }

                BigDecimal plannedQty = asBigDecimal(line.get("product_uom_qty"));
                BigDecimal targetDelivered;
                if (fullDelivery) {
                    targetDelivered = plannedQty.max(BigDecimal.ZERO);
                } else {
                    Integer done = partialDoneByProduct.getOrDefault(productId, 0);
                    targetDelivered = BigDecimal.valueOf(Math.max(done, 0L));
                    if (targetDelivered.compareTo(plannedQty) > 0) {
                        targetDelivered = plannedQty;
                    }
                }

                BigDecimal currentDelivered = asBigDecimal(line.get("qty_delivered"));
                if (currentDelivered.compareTo(targetDelivered) == 0) {
                    continue;
                }

                Map<String, Object> vals = new HashMap<>();
                vals.put("qty_delivered", targetDelivered);

                List<Object> writeArgs = List.of(
                        odooDb, odooUid, odooPassword,
                        "sale.order.line", "write",
                        List.of(List.of(lineId), vals)
                );
                Map<String, Object> writeResponse = callRpc(writeArgs);
                if (writeResponse == null || writeResponse.containsKey("error")) {
                    log.warn("Odoo syncSaleOrderLineDeliveredQuantities: write failed lineId={} erpOrderId={} response={}",
                            lineId, erpOrderId, writeResponse);
                    continue;
                }
                updated++;
            }

            log.info("Odoo syncSaleOrderLineDeliveredQuantities success in {}ms erpOrderId={} fullDelivery={} updatedLines={}",
                    System.currentTimeMillis() - start, erpOrderId, fullDelivery, updated);
            return true;
        } catch (Exception e) {
            log.warn("Odoo syncSaleOrderLineDeliveredQuantities exception erpOrderId={}", erpOrderId, e);
            return false;
        }
    }

    private Integer resolveProductIdFromPartialSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return null;
        }
        String normalized = sku.trim();
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            // continue
        }

        try {
            Map<String, Object> kwargs = new HashMap<>();
            kwargs.put("fields", List.of("id"));
            kwargs.put("limit", 1);
            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "product.product", "search_read",
                    List.of(List.of(
                        "|",
                            "|",
                            List.of("default_code", "=", normalized),
                        List.of("barcode", "=", normalized),
                            List.of("name", "ilike", normalized)
                    )),
                    kwargs
            );
            Map<String, Object> response = callRpc(args);
            if (response != null && !response.containsKey("error")) {
                List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getOrDefault("result", Collections.emptyList());
                if (!rows.isEmpty()) {
                    Integer id = asInt(rows.get(0).get("id"));
                    if (id != null) {
                        return id;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        Integer byName = searchProductByName(normalized);
        if (byName != null) {
            return byName;
        }

        log.warn("Odoo validatePartialTransfer: could not resolve product from partial SKU referenceKey={}", normalized);
        return null;
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

    @SuppressWarnings("unchecked")
    private boolean handleSaleCancelAction(Map<?, ?> action, Integer erpOrderId) {
        Object resModel = action.get("res_model");
        if (!"sale.order.cancel".equals(resModel)) {
            return false;
        }

        Integer wizardId = null;
        Object resId = action.get("res_id");
        if (resId instanceof Number n) {
            wizardId = n.intValue();
        }

        if (wizardId == null) {
            Map<String, Object> vals = new HashMap<>();
            vals.put("order_id", erpOrderId);

            List<Object> createArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "sale.order.cancel", "create",
                    List.of(vals)
            );
            Map<String, Object> createRes = callRpc(createArgs);
            if (createRes == null || createRes.containsKey("error") || !(createRes.get("result") instanceof Number n)) {
                return false;
            }
            wizardId = n.intValue();
        }

        List<Object> applyArgs = List.of(
                odooDb, odooUid, odooPassword,
                "sale.order.cancel", "action_cancel",
                List.of(List.of(wizardId))
        );
        Map<String, Object> applyRes = callRpc(applyArgs);
        return applyRes != null && !applyRes.containsKey("error");
    }

    @SuppressWarnings("unchecked")
    private boolean handleStockPickingAction(Map<?, ?> action, Integer pickingId, boolean cancelBackorder) {
        Object resModelObj = action.get("res_model");
        if (!(resModelObj instanceof String resModel)) {
            return false;
        }

        Map<String, Object> kwargs = new HashMap<>();
        if (action.get("context") instanceof Map<?, ?> c) {
            kwargs.put("context", c);
        }

        Integer wizardId = null;
        Object resId = action.get("res_id");
        if (resId instanceof Number n) {
            wizardId = n.intValue();
        }

        if ("stock.immediate.transfer".equals(resModel)) {
            if (wizardId == null) {
                Map<String, Object> vals = new HashMap<>();
                vals.put("pick_ids", List.of(List.of(6, 0, List.of(pickingId))));
                List<Object> createArgs = List.of(odooDb, odooUid, odooPassword, "stock.immediate.transfer", "create", List.of(vals), kwargs);
                Map<String, Object> createRes = callRpc(createArgs);
                if (createRes == null || createRes.containsKey("error")) {
                    log.warn("Odoo handleStockPickingAction: immediate transfer create failed: {}", createRes);
                    return false;
                }
                if (!(createRes.get("result") instanceof Number n2)) {
                    return false;
                }
                wizardId = n2.intValue();
            }

            List<Object> processArgs = List.of(odooDb, odooUid, odooPassword, "stock.immediate.transfer", "process", List.of(List.of(wizardId)), kwargs);
            Map<String, Object> processRes = callRpc(processArgs);
            if (processRes == null || processRes.containsKey("error")) {
                log.warn("Odoo handleStockPickingAction: immediate transfer process failed: {}", processRes);
                return false;
            }

            Object processResult = processRes.get("result");
            if (processResult instanceof Map<?, ?> nextAction) {
                return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            }
            return true;
        }

        if ("stock.backorder.confirmation".equals(resModel)) {
            if (wizardId == null) {
                Map<String, Object> vals = new HashMap<>();
                vals.put("pick_ids", List.of(List.of(6, 0, List.of(pickingId))));
                List<Object> createArgs = List.of(odooDb, odooUid, odooPassword, "stock.backorder.confirmation", "create", List.of(vals), kwargs);
                Map<String, Object> createRes = callRpc(createArgs);
                if (createRes == null || createRes.containsKey("error")) {
                    log.warn("Odoo handleStockPickingAction: backorder confirmation create failed: {}", createRes);
                    return false;
                }
                if (!(createRes.get("result") instanceof Number n2)) {
                    return false;
                }
                wizardId = n2.intValue();
            }

            String method = cancelBackorder ? "process_cancel_backorder" : "process";
            List<Object> processArgs = List.of(odooDb, odooUid, odooPassword, "stock.backorder.confirmation", method, List.of(List.of(wizardId)), kwargs);
            Map<String, Object> processRes = callRpc(processArgs);
            if (processRes == null || processRes.containsKey("error")) {
                log.warn("Odoo handleStockPickingAction: backorder confirmation process failed: {}", processRes);
                return false;
            }

            Object processResult = processRes.get("result");
            if (processResult instanceof Map<?, ?> nextAction) {
                return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            }
            return true;
        }

        if ("confirm.stock.sms".equals(resModel)) {
            if (wizardId == null) {
                return false;
            }

            List<Object> noSmsArgs = List.of(odooDb, odooUid, odooPassword, "confirm.stock.sms", "dont_send_sms", List.of(List.of(wizardId)), kwargs);
            Map<String, Object> noSmsRes = callRpc(noSmsArgs);
            if (noSmsRes == null || noSmsRes.containsKey("error")) {
                return false;
            }

            Object noSmsResult = noSmsRes.get("result");
            if (noSmsResult instanceof Map<?, ?> nextAction) {
                return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            }

            // SMS confirmation wizard may require a second button_validate call.
            List<Object> validateAgainArgs = List.of(
                    odooDb, odooUid, odooPassword,
                    "stock.picking", "button_validate",
                    List.of(List.of(pickingId))
            );
            Map<String, Object> validateAgainRes = callRpc(validateAgainArgs);
            if (validateAgainRes == null || validateAgainRes.containsKey("error")) {
                return false;
            }

            Object validateAgainResult = validateAgainRes.get("result");
            if (validateAgainResult instanceof Map<?, ?> nextAction) {
                return handleStockPickingAction(nextAction, pickingId, cancelBackorder);
            }
            return true;
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private String readPickingState(Integer pickingId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("state"));
        kwargs.put("limit", 1);

        List<Object> args = List.of(
                odooDb, odooUid, odooPassword,
                "stock.picking", "search_read",
                List.of(List.of(List.of("id", "=", pickingId))),
                kwargs
        );

        Map<String, Object> response = callRpc(args);
        if (response == null || response.containsKey("error")) {
            return null;
        }

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Object state = rows.get(0).get("state");
        return state != null ? String.valueOf(state) : null;
    }

    @SuppressWarnings("unchecked")
    private Integer findBackorderPickingId(Integer originPickingId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "state", "backorder_id"));
        kwargs.put("limit", 1);

        List<Object> args = List.of(
                odooDb, odooUid, odooPassword,
                "stock.picking", "search_read",
                List.of(List.of(List.of("backorder_id", "=", originPickingId))),
                kwargs
        );

        Map<String, Object> response = callRpc(args);
        if (response == null || response.containsKey("error")) {
            return null;
        }

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        return asInt(rows.get(0).get("id"));
    }

    @SuppressWarnings("unchecked")
    private String readSaleOrderState(Integer erpOrderId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("state"));
        kwargs.put("limit", 1);

        List<Object> args = List.of(
                odooDb, odooUid, odooPassword,
                "sale.order", "search_read",
                List.of(List.of(List.of("id", "=", erpOrderId))),
                kwargs
        );

        Map<String, Object> response = callRpc(args);
        if (response == null || response.containsKey("error")) {
            return null;
        }

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Object state = rows.get(0).get("state");
        return state != null ? String.valueOf(state) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findPickingById(Integer pickingId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "state", "origin", "sale_id"));
        kwargs.put("limit", 1);

        List<Object> args = List.of(
                odooDb, odooUid, odooPassword,
                "stock.picking", "search_read",
                List.of(List.of(List.of("id", "=", pickingId))),
                kwargs
        );

        Map<String, Object> response = callRpc(args);
        if (response == null || response.containsKey("error")) {
            log.warn("Odoo findPickingById: RPC error for pickingId={}", pickingId);
            return null;
        }

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findSinglePicking(Integer erpOrderId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "state", "origin", "sale_id"));
        kwargs.put("limit", 1);

        // Primary lookup by relational link.
        List<Object> bySaleIdArgs = List.of(
                odooDb, odooUid, odooPassword,
                "stock.picking", "search_read",
            List.of(List.of(
                List.of("sale_id", "=", erpOrderId),
                List.of("state", "not in", List.of("done", "cancel"))
            )),
                kwargs
        );

        Map<String, Object> bySaleIdResponse = callRpc(bySaleIdArgs);
        if (bySaleIdResponse != null && !bySaleIdResponse.containsKey("error")) {
            List<Map<String, Object>> pickings = (List<Map<String, Object>>) bySaleIdResponse.get("result");
            if (pickings != null && !pickings.isEmpty()) {
                return pickings.get(0);
            }
        }

        // Fallback lookup by sale order name in origin (common on some Odoo flows).
        String saleName = getSaleOrderName(erpOrderId);
        if (saleName == null || saleName.isBlank()) {
            return null;
        }

        List<Object> byOriginArgs = List.of(
                odooDb, odooUid, odooPassword,
                "stock.picking", "search_read",
            List.of(List.of(
                List.of("origin", "ilike", saleName),
                List.of("state", "not in", List.of("done", "cancel"))
            )),
                kwargs
        );

        Map<String, Object> byOriginResponse = callRpc(byOriginArgs);
        if (byOriginResponse == null || byOriginResponse.containsKey("error")) {
            return null;
        }

        List<Map<String, Object>> byOriginPickings = (List<Map<String, Object>>) byOriginResponse.get("result");
        if (byOriginPickings == null || byOriginPickings.isEmpty()) {
            return null;
        }
        return byOriginPickings.get(0);
    }

    @SuppressWarnings("unchecked")
    private String getSaleOrderName(Integer erpOrderId) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("fields", List.of("id", "name"));
        kwargs.put("limit", 1);

        List<Object> args = List.of(
                odooDb, odooUid, odooPassword,
                "sale.order", "search_read",
                List.of(List.of(List.of("id", "=", erpOrderId))),
                kwargs
        );

        Map<String, Object> response = callRpc(args);
        if (response == null || response.containsKey("error")) {
            return null;
        }

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("result");
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        Object name = rows.get(0).get("name");
        return name != null ? String.valueOf(name) : null;
    }

    public String getSaleOrderReference(Integer erpOrderId) {
        return getSaleOrderName(erpOrderId);
    }
}
