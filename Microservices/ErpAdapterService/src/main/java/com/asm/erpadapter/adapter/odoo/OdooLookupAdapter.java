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
 * Odoo implementation of ErpLookupPort.
 *
 * Searches clients, products, pending orders, and previews from Odoo.
 * Moved from ErpLookupService's Odoo-specific query logic.
 * Bean name must match the sync adapter key ("odoo") for router.
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
                    "|", List.of("phone", "ilike", term),
                    List.of("mobile", "ilike", term),
                    List.of("customer_rank", ">", 0),
                    List.of("active", "=", true));

            List<Map<String, Object>> rows = rpc.searchRead("res.partner", domain,
                    List.of("id", "name", "phone", "mobile", "email"), limit, "name asc");

            List<ErpClientDTO> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Integer id = asInt(row.get("id"));
                if (id == null) continue;
                result.add(ErpClientDTO.builder()
                        .erpClientId(String.valueOf(id))
                        .name(orEmpty(asString(row.get("name"))))
                        .phone(firstNonBlank(asString(row.get("phone")), asString(row.get("mobile"))))
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

    // ── Pending Orders ──────────────────────────────────────────────────────────

    @Override
    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) {
        long start = System.currentTimeMillis();
        try {
            List<Object> domain = List.of(List.of("state", "!=", "cancel"));

            List<Map<String, Object>> rows = rpc.searchRead("sale.order", domain,
                    List.of("id", "name", "client_order_ref", "partner_id", "partner_shipping_id",
                            "amount_total", "currency_id", "state", "invoice_status",
                            "date_order", "commitment_date"),
                    limit, "date_order desc");

            Map<Integer, Map<String, Object>> partners = fetchPartnersForOrders(rows);

            List<ErpPendingOrderSummaryDTO> summaries = rows.stream()
                    .map(row -> mapToSummary(row, partners))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ErpPendingOrderSummaryDTO::getDateOrder,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());

            log.info("getPendingOrders count={} durationMs={}", summaries.size(), System.currentTimeMillis() - start);
            return summaries;
        } catch (Exception e) {
            log.warn("getPendingOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Order Preview ───────────────────────────────────────────────────────────

    @Override
    public ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId) {
        Map<String, Object> row = fetchSaleOrderByRef(erpOrderId);
        if (row == null) return null;

        Map<Integer, Map<String, Object>> partners = fetchPartnersForOrders(List.of(row));
        Map<String, Object> addressPartner = resolveAddressPartner(row, partners);

        List<ErpOrderItemDTO> items = fetchOrderItems(row);
        int totalQty = items.stream()
                .map(i -> i.getQuantity() != null ? i.getQuantity() : 0)
                .reduce(0, Integer::sum);
        BigDecimal totalWeight = items.stream()
                .map(i -> {
                    BigDecimal w = i.getUnitWeightKg() != null ? i.getUnitWeightKg() : BigDecimal.ZERO;
                    int q = i.getQuantity() != null ? i.getQuantity() : 0;
                    return w.multiply(BigDecimal.valueOf(q));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ErpPendingOrderPreviewDTO.builder()
                .erpOrderId(asString(row.get("name")))
                .externalRef(asString(row.get("client_order_ref")))
                .customerName(resolveCustomerName(row, addressPartner))
                .customerPhone(resolveCustomerPhone(addressPartner))
                .deliveryAddress(buildAddress(addressPartner))
                .deliveryCity(addressPartner != null ? asString(addressPartner.get("city")) : null)
                .deliveryInstructions(asString(row.get("note")))
                .totalAmount(asBigDecimal(row.get("amount_total")))
                .currency(resolveCurrency(row))
                .priority("NORMAL")
                .dateOrder(parseOdooDateTime(row.get("date_order")))
                .scheduledAt(parseOdooDateTime(row.get("commitment_date")))
                .items(items)
                .totalQuantity(totalQty)
                .totalWeightKg(totalWeight)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<Integer, Map<String, Object>> fetchPartnersForOrders(List<Map<String, Object>> rows) {
        Set<Integer> partnerIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Integer shippingId = asRelId(row.get("partner_shipping_id"));
            Integer partnerId = asRelId(row.get("partner_id"));
            if (shippingId != null) partnerIds.add(shippingId);
            if (partnerId != null) partnerIds.add(partnerId);
        }
        if (partnerIds.isEmpty()) return Map.of();

        List<Map<String, Object>> partnerRows = rpc.searchRead("res.partner",
                List.of(List.of("id", "in", partnerIds.stream().toList())),
                List.of("id", "name", "phone", "mobile", "street", "street2", "city", "zip"),
                Math.max(partnerIds.size(), 1), "id asc");

        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> pr : partnerRows) {
            Integer id = asInt(pr.get("id"));
            if (id != null) result.put(id, pr);
        }
        return result;
    }

    private Map<String, Object> fetchSaleOrderByRef(String erpOrderId) {
        String normalized = asString(erpOrderId);
        if (normalized == null) return null;

        List<Map<String, Object>> rows = rpc.searchRead("sale.order",
                List.of(List.of("name", "=", normalized)),
                List.of("id", "name", "client_order_ref", "partner_id", "partner_shipping_id",
                        "amount_total", "currency_id", "state", "invoice_status",
                        "date_order", "commitment_date", "note", "order_line", "invoice_ids"),
                1, "id desc");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ErpOrderItemDTO> fetchOrderItems(Map<String, Object> saleOrderRow) {
        List<Integer> lineIds = asIdList(saleOrderRow.get("order_line"));
        if (lineIds.isEmpty()) return List.of();

        List<Map<String, Object>> lineRows = rpc.searchRead("sale.order.line",
                List.of(List.of("id", "in", lineIds)),
                List.of("id", "product_id", "name", "product_uom_qty", "qty_delivered", "price_unit"),
                Math.max(lineIds.size(), 1), "id asc");

        Set<Integer> productIds = lineRows.stream()
                .map(l -> asRelId(l.get("product_id")))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Integer, BigDecimal> weights = fetchProductWeights(productIds);

        return lineRows.stream().map(line -> {
            Integer productId = asRelId(line.get("product_id"));
            String productName = asRelName(line.get("product_id"));
            String name = firstNonBlank(asString(line.get("name")), productName, "ERP Item");
            Integer qty = asInt(line.get("product_uom_qty"));
            BigDecimal unitWeight = productId != null ? weights.getOrDefault(productId, BigDecimal.ZERO) : BigDecimal.ZERO;

            return ErpOrderItemDTO.builder()
                    .name(name)
                    .sku(productId != null ? String.valueOf(productId) : null)
                    .quantity(qty != null && qty > 0 ? qty : 1)
                    .unitPrice(asBigDecimal(line.get("price_unit")))
                    .unitWeightKg(unitWeight)
                    .build();
        }).collect(Collectors.toList());
    }

    private Map<Integer, BigDecimal> fetchProductWeights(Set<Integer> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();
        List<Map<String, Object>> rows = rpc.searchRead("product.product",
                List.of(List.of("id", "in", productIds.stream().toList())),
                List.of("id", "weight"), Math.max(productIds.size(), 1), "id asc");
        Map<Integer, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer id = asInt(row.get("id"));
            if (id != null) result.put(id, asBigDecimal(row.get("weight")));
        }
        return result;
    }

    private ErpPendingOrderSummaryDTO mapToSummary(Map<String, Object> row, Map<Integer, Map<String, Object>> partners) {
        String erpOrderId = asString(row.get("name"));
        if (erpOrderId == null) return null;

        Map<String, Object> addressPartner = resolveAddressPartner(row, partners);

        return ErpPendingOrderSummaryDTO.builder()
                .erpOrderId(erpOrderId)
                .externalRef(asString(row.get("client_order_ref")))
                .customerName(resolveCustomerName(row, addressPartner))
                .customerPhone(resolveCustomerPhone(addressPartner))
                .deliveryAddress(buildAddress(addressPartner))
                .deliveryCity(addressPartner != null ? asString(addressPartner.get("city")) : null)
                .totalAmount(asBigDecimal(row.get("amount_total")))
                .currency(resolveCurrency(row))
                .state(asString(row.get("state")))
                .invoiceStatus(asString(row.get("invoice_status")))
                .dateOrder(parseOdooDateTime(row.get("date_order")))
                .scheduledAt(parseOdooDateTime(row.get("commitment_date")))
                .build();
    }

    private Map<String, Object> resolveAddressPartner(Map<String, Object> order, Map<Integer, Map<String, Object>> partners) {
        Integer shippingId = asRelId(order.get("partner_shipping_id"));
        Integer partnerId = asRelId(order.get("partner_id"));
        if (shippingId != null && partners.containsKey(shippingId)) return partners.get(shippingId);
        if (partnerId != null) return partners.get(partnerId);
        return null;
    }

    private String resolveCustomerName(Map<String, Object> order, Map<String, Object> partner) {
        String partnerName = asRelName(order.get("partner_id"));
        String shippingName = partner != null ? asString(partner.get("name")) : null;
        return firstNonBlank(shippingName, partnerName, "ERP Customer");
    }

    private String resolveCustomerPhone(Map<String, Object> partner) {
        if (partner == null) return null;
        return firstNonBlank(asString(partner.get("phone")), asString(partner.get("mobile")));
    }

    private String resolveCurrency(Map<String, Object> order) {
        String raw = asRelName(order.get("currency_id"));
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
