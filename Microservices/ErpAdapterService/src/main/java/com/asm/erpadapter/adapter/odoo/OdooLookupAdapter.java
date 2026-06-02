package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.dto.*;
import com.asm.erpadapter.port.ErpLookupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Odoo implementation of {@link ErpLookupPort} (Odoo 19).
 *
 * <p>Enterprise multi-depot model: the source of truth for what to deliver is the
 * Odoo <b>delivery order / bon de livraison</b> ({@code stock.picking}, outgoing).
 * A ready picking ({@code state = 'assigned'}) carries the official BL number
 * ({@code name}), the <b>source warehouse</b> (→ ASM depot), and the line items
 * actually being shipped. We therefore list/preview <b>ready delivery notes</b>,
 * not raw confirmed sale orders. A sale order that ships from two warehouses yields
 * two delivery notes → two ASM deliveries from two depots (natural multi-depot).
 *
 * <p>Operator-facing reads use {@link OdooJsonRpcClient#searchReadStrict} so a
 * misconfigured Odoo (wrong field/model/access) surfaces a structured error rather
 * than a silently empty page.
 */
@Component("odooLookup")
@RequiredArgsConstructor
@Slf4j
public class OdooLookupAdapter implements ErpLookupPort {

    private final OdooJsonRpcClient rpc;

    // ── Search Clients ──────────────────────────────────────────────────────────

    @Override
    public List<ErpClientDTO> searchClients(String search, int limit) {
        long start = System.currentTimeMillis();
        try {
            String term = search != null ? search.trim() : "";
            List<Object> domain = List.of(
                    "|", List.of("name", "ilike", term),
                    List.of("phone", "ilike", term),
                    List.of("customer_rank", ">", 0),
                    List.of("active", "=", true));

            List<Map<String, Object>> rows = rpc.searchRead("res.partner", domain,
                    List.of("id", "name", "phone", "email"), limit, "name asc");

            List<ErpClientDTO> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Integer id = asInt(row.get("id"));
                if (id == null) continue;
                result.add(ErpClientDTO.builder()
                        .erpClientId(String.valueOf(id))
                        .name(orEmpty(asString(row.get("name"))))
                        .phone(asString(row.get("phone")))
                        .email(asString(row.get("email")))
                        .build());
            }

            log.info("searchClients term='{}' count={} durationMs={}", term, result.size(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("searchClients failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Search Products ─────────────────────────────────────────────────────────

    @Override
    public List<ErpProductDTO> searchProducts(String search, int limit) {
        long start = System.currentTimeMillis();
        try {
            String term = search != null ? search.trim() : "";
            List<Object> domain = List.of(
                    List.of("sale_ok", "=", true),
                    List.of("active", "=", true),
                    "|", List.of("name", "ilike", term),
                    List.of("default_code", "ilike", term));

            List<Map<String, Object>> rows = rpc.searchRead("product.product", domain,
                    List.of("id", "name", "default_code", "list_price", "qty_available", "description_sale", "weight"),
                    limit, "name asc");

            List<ErpProductDTO> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Integer id = asInt(row.get("id"));
                if (id == null) continue;
                int stock = asInt(row.get("qty_available")) != null ? asInt(row.get("qty_available")) : 0;
                double price = asDouble(row.get("list_price")) != null ? asDouble(row.get("list_price")) : 0;
                double weight = asDouble(row.get("weight")) != null ? asDouble(row.get("weight")) : 0;

                result.add(ErpProductDTO.builder()
                        .erpProductId(String.valueOf(id))
                        .name(orEmpty(asString(row.get("name"))))
                        .sku(asString(row.get("default_code")))
                        .price(price).stock(stock).available(stock > 0)
                        .description(asString(row.get("description_sale")))
                        .weightKg(weight)
                        .build());
            }

            log.info("searchProducts term='{}' count={} durationMs={}", term, result.size(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("searchProducts failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Pending delivery notes (ready outgoing pickings) ──────────────────────────

    private static final List<Object> READY_DELIVERY_DOMAIN = List.of(
            List.of("picking_type_id.code", "=", "outgoing"),
            List.of("state", "=", "assigned"));

    private static final List<String> PICKING_FIELDS = List.of(
            "id", "name", "origin", "state", "partner_id",
            "scheduled_date", "date_deadline", "picking_type_id", "sale_id");

    @Override
    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> pickings = rpc.searchReadStrict(
                "stock.picking", READY_DELIVERY_DOMAIN, PICKING_FIELDS, limit, "scheduled_date asc");
        if (pickings.isEmpty()) return List.of();

        Map<Integer, Warehouse> warehouses = resolveWarehouses(pickings);
        Map<Integer, Map<String, Object>> partners = fetchPartnersByIds(relIds(pickings, "partner_id"));
        Map<Integer, Map<String, Object>> saleOrders = fetchSaleOrdersByIds(relIds(pickings, "sale_id"));

        List<ErpPendingOrderSummaryDTO> summaries = pickings.stream()
                .map(p -> mapToSummary(p, warehouses, partners, saleOrders))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.info("getPendingOrders (ready BLs) count={} durationMs={}", summaries.size(), System.currentTimeMillis() - start);
        return summaries;
    }

    // ── Delivery-note preview (single picking, full detail) ───────────────────────

    @Override
    public ErpPendingOrderPreviewDTO getPendingOrderPreview(String blNumber) {
        Map<String, Object> picking = fetchPickingByName(blNumber);
        if (picking == null) return null;

        Integer pickingId = asInt(picking.get("id"));
        Map<Integer, Warehouse> warehouses = resolveWarehouses(List.of(picking));
        Warehouse wh = warehouses.get(asRelId(picking.get("picking_type_id")));

        Map<Integer, Map<String, Object>> partners = fetchPartnersByIds(relIds(List.of(picking), "partner_id"));
        Map<String, Object> partner = partners.get(asRelId(picking.get("partner_id")));

        Map<Integer, Map<String, Object>> saleOrders = fetchSaleOrdersByIds(relIds(List.of(picking), "sale_id"));
        Map<String, Object> sale = saleOrders.get(asRelId(picking.get("sale_id")));

        List<ErpOrderItemDTO> items = pickingId != null ? fetchItemsFromPicking(pickingId) : List.of();
        int totalQty = items.stream().map(i -> i.getQuantity() != null ? i.getQuantity() : 0).reduce(0, Integer::sum);
        BigDecimal totalWeight = items.stream()
                .map(i -> {
                    BigDecimal w = i.getUnitWeightKg() != null ? i.getUnitWeightKg() : BigDecimal.ZERO;
                    int q = i.getQuantity() != null ? i.getQuantity() : 0;
                    return w.multiply(BigDecimal.valueOf(q));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean ready = "assigned".equals(asString(picking.get("state")));

        return ErpPendingOrderPreviewDTO.builder()
                .erpOrderId(asString(picking.get("name")))            // import identity = the BL number
                .blNumber(asString(picking.get("name")))
                .saleOrderRef(firstNonBlank(asRelName(picking.get("sale_id")), asString(picking.get("origin"))))
                .externalRef(sale != null ? asString(sale.get("client_order_ref")) : null)
                .warehouseCode(wh != null ? wh.code() : null)
                .warehouseName(wh != null ? wh.name() : null)
                .ready(ready)
                .customerName(resolveCustomerName(sale, partner, picking))
                .customerPhone(partner != null ? asString(partner.get("phone")) : null)
                .deliveryAddress(buildAddress(partner))
                .deliveryCity(partner != null ? asString(partner.get("city")) : null)
                .deliveryInstructions(sale != null ? asString(sale.get("note")) : null)
                .totalAmount(sale != null ? asBigDecimal(sale.get("amount_total")) : null)
                .currency(resolveCurrency(sale))
                .paymentTermName(sale != null ? asRelName(sale.get("payment_term_id")) : null)
                .priority("NORMAL")
                .dateOrder(sale != null ? parseOdooDateTime(sale.get("date_order")) : null)
                .scheduledAt(parseOdooDateTime(picking.get("scheduled_date")))
                .items(items)
                .totalQuantity(totalQty)
                .totalWeightKg(totalWeight)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private record Warehouse(String code, String name) {}

    private Map<String, Object> fetchPickingByName(String blNumber) {
        String name = asString(blNumber);
        if (name == null) return null;
        List<Map<String, Object>> rows = rpc.searchReadStrict("stock.picking",
                List.of(List.of("name", "=", name), List.of("picking_type_id.code", "=", "outgoing")),
                PICKING_FIELDS, 1, "id desc");
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** picking → picking_type → warehouse (code + name). Keyed by picking_type_id. */
    private Map<Integer, Warehouse> resolveWarehouses(List<Map<String, Object>> pickings) {
        Set<Integer> typeIds = relIds(pickings, "picking_type_id");
        if (typeIds.isEmpty()) return Map.of();

        List<Map<String, Object>> typeRows = rpc.searchReadStrict("stock.picking.type",
                List.of(List.of("id", "in", typeIds.stream().toList())),
                List.of("id", "warehouse_id"), typeIds.size(), "id asc");

        Map<Integer, Integer> typeToWarehouseId = new HashMap<>();
        for (Map<String, Object> t : typeRows) {
            Integer tid = asInt(t.get("id"));
            Integer wid = asRelId(t.get("warehouse_id"));
            if (tid != null && wid != null) typeToWarehouseId.put(tid, wid);
        }
        if (typeToWarehouseId.isEmpty()) return Map.of();

        Set<Integer> whIds = new HashSet<>(typeToWarehouseId.values());
        List<Map<String, Object>> whRows = rpc.searchReadStrict("stock.warehouse",
                List.of(List.of("id", "in", whIds.stream().toList())),
                List.of("id", "code", "name"), whIds.size(), "id asc");

        Map<Integer, Warehouse> whById = new HashMap<>();
        for (Map<String, Object> w : whRows) {
            Integer id = asInt(w.get("id"));
            if (id != null) whById.put(id, new Warehouse(asString(w.get("code")), asString(w.get("name"))));
        }

        Map<Integer, Warehouse> byType = new HashMap<>();
        typeToWarehouseId.forEach((tid, wid) -> {
            Warehouse w = whById.get(wid);
            if (w != null) byType.put(tid, w);
        });
        return byType;
    }

    private Map<Integer, Map<String, Object>> fetchPartnersByIds(Set<Integer> partnerIds) {
        if (partnerIds.isEmpty()) return Map.of();
        List<Map<String, Object>> rows = rpc.searchReadStrict("res.partner",
                List.of(List.of("id", "in", partnerIds.stream().toList())),
                List.of("id", "name", "phone", "street", "street2", "city", "zip"),
                partnerIds.size(), "id asc");
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> pr : rows) {
            Integer id = asInt(pr.get("id"));
            if (id != null) result.put(id, pr);
        }
        return result;
    }

    private Map<Integer, Map<String, Object>> fetchSaleOrdersByIds(Set<Integer> saleIds) {
        if (saleIds.isEmpty()) return Map.of();
        List<Map<String, Object>> rows = rpc.searchReadStrict("sale.order",
                List.of(List.of("id", "in", saleIds.stream().toList())),
                List.of("id", "name", "client_order_ref", "partner_id", "amount_total",
                        "currency_id", "payment_term_id", "note", "date_order"),
                saleIds.size(), "id asc");
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Integer id = asInt(r.get("id"));
            if (id != null) result.put(id, r);
        }
        return result;
    }

    /** Line items actually shipped on this delivery note (the picking's stock moves). */
    private List<ErpOrderItemDTO> fetchItemsFromPicking(int pickingId) {
        List<Map<String, Object>> moves = rpc.searchReadStrict("stock.move",
                List.of(List.of("picking_id", "=", pickingId)),
                List.of("id", "product_id", "name", "product_uom_qty"), 0, "id asc");
        if (moves.isEmpty()) return List.of();

        Set<Integer> productIds = moves.stream()
                .map(m -> asRelId(m.get("product_id"))).filter(Objects::nonNull).collect(Collectors.toSet());
        ProductDetails pd = fetchProductDetails(productIds);

        return moves.stream().map(m -> {
            Integer productId = asRelId(m.get("product_id"));
            String name = firstNonBlank(asRelName(m.get("product_id")), asString(m.get("name")), "ERP Item");
            int qty = (int) Math.round(asDouble(m.get("product_uom_qty")) != null ? asDouble(m.get("product_uom_qty")) : 1d);
            BigDecimal unitWeight = productId != null ? pd.weights.getOrDefault(productId, BigDecimal.ZERO) : BigDecimal.ZERO;
            String sku  = productId != null ? pd.skus.get(productId)  : null;
            String type = productId != null ? pd.types.get(productId) : null;
            return ErpOrderItemDTO.builder()
                    .name(name)
                    .sku(sku)
                    .quantity(qty > 0 ? qty : 1)
                    .unitPrice(null) // delivery moves carry no price; order total comes from the sale order
                    .unitWeightKg(unitWeight)
                    .productType(type)
                    .build();
        }).collect(Collectors.toList());
    }

    private record ProductDetails(Map<Integer, BigDecimal> weights, Map<Integer, String> skus, Map<Integer, String> types) {}

    private ProductDetails fetchProductDetails(Set<Integer> productIds) {
        if (productIds == null || productIds.isEmpty()) return new ProductDetails(Map.of(), Map.of(), Map.of());
        // Odoo 19: product type lives on `type` (consu/service/combo); `is_storable` flags stockable goods.
        List<Map<String, Object>> rows = rpc.searchReadStrict("product.product",
                List.of(List.of("id", "in", productIds.stream().toList())),
                List.of("id", "weight", "default_code", "type"), productIds.size(), "id asc");
        Map<Integer, BigDecimal> weights = new HashMap<>();
        Map<Integer, String>     skus    = new HashMap<>();
        Map<Integer, String>     types   = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer id = asInt(row.get("id"));
            if (id == null) continue;
            weights.put(id, asBigDecimal(row.get("weight")));
            String dc = asString(row.get("default_code"));
            if (dc != null && !dc.isBlank()) skus.put(id, dc);
            String dt = asString(row.get("type"));
            if (dt != null && !dt.isBlank()) types.put(id, dt);
        }
        return new ProductDetails(weights, skus, types);
    }

    private ErpPendingOrderSummaryDTO mapToSummary(Map<String, Object> picking,
                                                   Map<Integer, Warehouse> warehouses,
                                                   Map<Integer, Map<String, Object>> partners,
                                                   Map<Integer, Map<String, Object>> saleOrders) {
        String bl = asString(picking.get("name"));
        if (bl == null) return null;

        Warehouse wh = warehouses.get(asRelId(picking.get("picking_type_id")));
        Map<String, Object> partner = partners.get(asRelId(picking.get("partner_id")));
        Map<String, Object> sale = saleOrders.get(asRelId(picking.get("sale_id")));

        return ErpPendingOrderSummaryDTO.builder()
                .erpOrderId(bl)                                       // import identity = BL number
                .blNumber(bl)
                .saleOrderRef(firstNonBlank(asRelName(picking.get("sale_id")), asString(picking.get("origin"))))
                .externalRef(sale != null ? asString(sale.get("client_order_ref")) : null)
                .warehouseCode(wh != null ? wh.code() : null)
                .warehouseName(wh != null ? wh.name() : null)
                .ready("assigned".equals(asString(picking.get("state"))))
                .customerName(resolveCustomerName(sale, partner, picking))
                .customerPhone(partner != null ? asString(partner.get("phone")) : null)
                .deliveryAddress(buildAddress(partner))
                .deliveryCity(partner != null ? asString(partner.get("city")) : null)
                .totalAmount(sale != null ? asBigDecimal(sale.get("amount_total")) : null)
                .currency(resolveCurrency(sale))
                .state(asString(picking.get("state")))
                .dateOrder(sale != null ? parseOdooDateTime(sale.get("date_order")) : null)
                .scheduledAt(parseOdooDateTime(picking.get("scheduled_date")))
                .build();
    }

    // ── Small resolvers ───────────────────────────────────────────────────────

    /** Collect the related-record ids for a Many2one field across rows. */
    private static Set<Integer> relIds(List<Map<String, Object>> rows, String field) {
        Set<Integer> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Integer id = asRelId(row.get(field));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private String resolveCustomerName(Map<String, Object> sale, Map<String, Object> partner, Map<String, Object> picking) {
        String partnerName = partner != null ? asString(partner.get("name")) : null;
        String pickingPartner = asRelName(picking.get("partner_id"));
        String salePartner = sale != null ? asRelName(sale.get("partner_id")) : null;
        return firstNonBlank(partnerName, pickingPartner, salePartner, "ERP Customer");
    }

    private String resolveCurrency(Map<String, Object> sale) {
        if (sale == null) return "TND";
        String raw = asRelName(sale.get("currency_id"));
        if (raw == null || raw.length() < 3) return "TND";
        return raw.substring(0, 3).toUpperCase(Locale.ROOT);
    }

    private static String buildAddress(Map<String, Object> partner) {
        if (partner == null) return null;
        List<String> parts = new ArrayList<>();
        String street = asString(partner.get("street"));
        String street2 = asString(partner.get("street2"));
        String zip = asString(partner.get("zip"));
        if (street != null) parts.add(street);
        if (street2 != null) parts.add(street2);
        if (zip != null) parts.add(zip);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static LocalDateTime parseOdooDateTime(Object value) {
        String text = asString(value);
        if (text == null) return null;
        try { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
        catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(text.replace("Z", "")); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
