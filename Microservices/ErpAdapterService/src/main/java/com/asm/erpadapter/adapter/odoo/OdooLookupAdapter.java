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
 * Odoo implementation of {@link ErpLookupPort}.
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

    // Static import of the mapped fields keeps the builder chain readable.
    private static final com.asm.erpadapter.mapping.CanonicalField
            CUSTOMER_NAME = com.asm.erpadapter.mapping.CanonicalField.CUSTOMER_NAME,
            CUSTOMER_PHONE = com.asm.erpadapter.mapping.CanonicalField.CUSTOMER_PHONE,
            DELIVERY_POSTAL_CODE = com.asm.erpadapter.mapping.CanonicalField.DELIVERY_POSTAL_CODE,
            DELIVERY_ADDRESS = com.asm.erpadapter.mapping.CanonicalField.DELIVERY_ADDRESS,
            DELIVERY_CITY = com.asm.erpadapter.mapping.CanonicalField.DELIVERY_CITY,
            DELIVERY_INSTRUCTIONS = com.asm.erpadapter.mapping.CanonicalField.DELIVERY_INSTRUCTIONS,
            SALE_ORDER_REF = com.asm.erpadapter.mapping.CanonicalField.SALE_ORDER_REF,
            CUSTOMER_REF = com.asm.erpadapter.mapping.CanonicalField.CUSTOMER_REF,
            CURRENCY = com.asm.erpadapter.mapping.CanonicalField.CURRENCY,
            PRIORITY = com.asm.erpadapter.mapping.CanonicalField.PRIORITY,
            ERP_ORDER_ID = com.asm.erpadapter.mapping.CanonicalField.ERP_ORDER_ID,
            BL_NUMBER = com.asm.erpadapter.mapping.CanonicalField.BL_NUMBER,
            TOTAL_AMOUNT = com.asm.erpadapter.mapping.CanonicalField.TOTAL_AMOUNT,
            COD_REQUIRED = com.asm.erpadapter.mapping.CanonicalField.COD_REQUIRED,
            COD_AMOUNT = com.asm.erpadapter.mapping.CanonicalField.COD_AMOUNT,
            DATE_ORDER = com.asm.erpadapter.mapping.CanonicalField.DATE_ORDER,
            SCHEDULED_AT = com.asm.erpadapter.mapping.CanonicalField.SCHEDULED_AT,
            WAREHOUSE_CODE = com.asm.erpadapter.mapping.CanonicalField.WAREHOUSE_CODE,
            WAREHOUSE_NAME = com.asm.erpadapter.mapping.CanonicalField.WAREHOUSE_NAME,
            READY = com.asm.erpadapter.mapping.CanonicalField.READY,
            ITEM_SKU = com.asm.erpadapter.mapping.CanonicalField.ITEM_SKU,
            ITEM_NAME = com.asm.erpadapter.mapping.CanonicalField.ITEM_NAME,
            ITEM_QUANTITY = com.asm.erpadapter.mapping.CanonicalField.ITEM_QUANTITY,
            ITEM_UNIT_PRICE = com.asm.erpadapter.mapping.CanonicalField.ITEM_UNIT_PRICE,
            ITEM_UNIT_WEIGHT_KG = com.asm.erpadapter.mapping.CanonicalField.ITEM_UNIT_WEIGHT_KG,
            ITEM_PRODUCT_TYPE = com.asm.erpadapter.mapping.CanonicalField.ITEM_PRODUCT_TYPE;

    private final OdooJsonRpcClient rpc;
    private final com.asm.erpadapter.mapping.OdooFieldMappingResolver fieldMapping;

    /**
     * Applies the tenant's field mapping, falling back to this class's own reader.
     *
     * <p>Every business value below goes through here. When the tenant has mapped nothing — the case
     * for every existing customer — the supplier runs and the result is identical to before mapping
     * existed, which is what makes this safe to introduce against ERPs already in production.
     */
    private String mappedString(com.asm.erpadapter.mapping.CanonicalField field,
                                Map<String, Map<String, Object>> records,
                                java.util.function.Supplier<String> builtIn) {
        Object v = fieldMapping.resolveOrDefault(field, records, builtIn::get);
        return v == null ? null : (v instanceof String s ? s : String.valueOf(v));
    }

    /*
     * Typed variants of the same idea. A mapped field arrives as whatever Odoo stores — a float where
     * ASM wants a BigDecimal, the string "2026-07-28 09:00:00" where it wants a LocalDateTime — so the
     * value is coerced here rather than at twenty call sites. A value that cannot be coerced yields
     * null instead of throwing: one bad mapping must not abort an otherwise valid import, and the
     * blank is visible in the preview, which is where the integrator is looking.
     */

    private BigDecimal mappedDecimal(com.asm.erpadapter.mapping.CanonicalField field,
                                     Map<String, Map<String, Object>> records,
                                     java.util.function.Supplier<BigDecimal> builtIn) {
        Object v = fieldMapping.resolveOrDefault(field, records, builtIn::get);
        // "Absent" must survive as absent. The shared asBigDecimal coerces both null and Odoo's
        // `false` (its empty marker) to ZERO, which is right when summing a column and wrong here:
        // the import list deliberately passes a null default for COD_AMOUNT so the cell stays blank,
        // and ZERO turned that into a printed "0" beside a badge saying money is due. Zero to
        // collect and nothing known are opposite instructions to a driver.
        return v == null || Boolean.FALSE.equals(v) ? null : asBigDecimal(v);
    }

    private Integer mappedInt(com.asm.erpadapter.mapping.CanonicalField field,
                              Map<String, Map<String, Object>> records,
                              java.util.function.Supplier<Integer> builtIn) {
        Object v = fieldMapping.resolveOrDefault(field, records, builtIn::get);
        if (v instanceof Integer i) return i;
        Double d = asDouble(v);
        return d == null ? null : (int) Math.round(d);
    }

    private java.time.LocalDateTime mappedDateTime(com.asm.erpadapter.mapping.CanonicalField field,
                                                   Map<String, Map<String, Object>> records,
                                                   java.util.function.Supplier<java.time.LocalDateTime> builtIn) {
        Object v = fieldMapping.resolveOrDefault(field, records, builtIn::get);
        if (v instanceof java.time.LocalDateTime dt) return dt;
        return parseOdooDateTime(v);
    }

    private boolean mappedBoolean(com.asm.erpadapter.mapping.CanonicalField field,
                                  Map<String, Map<String, Object>> records,
                                  java.util.function.Supplier<Boolean> builtIn) {
        Object v = fieldMapping.resolveOrDefault(field, records, builtIn::get);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0d;
        // A customer often flags readiness with a status word rather than a checkbox.
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            return t.equals("true") || t.equals("assigned") || t.equals("ready")
                    || t.equals("done") || t.equals("1") || t.equals("yes");
        }
        return false;
    }


    /**
     * The adapter's own field list, widened by whatever this tenant has mapped.
     *
     * <p>Without this a mapping onto a field the adapter never requests resolves to nothing, and the
     * integrator cannot tell an empty ERP field from one that was never fetched.
     */
    private List<String> withMappedFields(String model, List<String> base) {
        java.util.Set<String> extra = fieldMapping.extraFieldsFor(model);
        if (extra.isEmpty()) return base;
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(base);
        all.addAll(extra);
        return List.copyOf(all);
    }

    /** Keep an untouched order's payload free of an empty object nobody will render. */
    private static Map<String, Object> emptyToNull(Map<String, Object> m) {
        return (m == null || m.isEmpty()) ? null : m;
    }

    /** The documents a mapping path may address, for one order. */
    private static Map<String, Map<String, Object>> scope(Map<String, Object> picking,
                                                          Map<String, Object> sale,
                                                          Map<String, Object> partner) {
        Map<String, Map<String, Object>> records = new java.util.HashMap<>();
        if (picking != null) records.put("stock.picking", picking);
        if (sale != null) records.put("sale.order", sale);
        if (partner != null) records.put("res.partner", partner);
        return records;
    }

    /**
     * The documents in scope for one order <em>line</em>.
     *
     * <p>The header records stay addressable on purpose: a per-line value is sometimes carried on the
     * order rather than the move, and re-deriving the header scope per line costs nothing since the
     * maps are already in hand.
     */
    private static Map<String, Map<String, Object>> lineScope(Map<String, Map<String, Object>> header,
                                                              Map<String, Object> move,
                                                              Map<String, Object> product,
                                                              Map<String, Object> saleLine) {
        Map<String, Map<String, Object>> records = new java.util.HashMap<>(header);
        if (move != null) records.put("stock.move", move);
        if (product != null) records.put("product.product", product);
        if (saleLine != null) records.put("sale.order.line", saleLine);
        return records;
    }

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
            "scheduled_date", "date_deadline", "picking_type_id", "sale_id", "backorder_id");

    @Override
    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> pickings = rpc.searchReadStrict(
                "stock.picking", READY_DELIVERY_DOMAIN, withMappedFields("stock.picking", PICKING_FIELDS), limit, "id desc");
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
        
        Integer typeId = asRelId(picking.get("picking_type_id"));
        Map<Integer, Warehouse> warehouses = resolveWarehouses(List.of(picking));
        Warehouse wh = typeId != null ? warehouses.get(typeId) : null;

        Integer partnerId = asRelId(picking.get("partner_id"));
        Map<Integer, Map<String, Object>> partners = fetchPartnersByIds(relIds(List.of(picking), "partner_id"));
        Map<String, Object> partner = partnerId != null ? partners.get(partnerId) : null;

        Integer saleId = asRelId(picking.get("sale_id"));
        Map<Integer, Map<String, Object>> saleOrders = fetchSaleOrdersByIds(relIds(List.of(picking), "sale_id"));
        Map<String, Object> sale = saleId != null ? saleOrders.get(saleId) : null;

        final Map<String, Map<String, Object>> records = scope(picking, sale, partner);
        final Map<String, Object> saleRef = sale;
        final Map<String, Object> partnerRef = partner;

        // Built before the totals: a mapped quantity or unit weight changes what they sum to.
        PickingLines lines = pickingId != null
                ? fetchItemsFromPicking(pickingId, saleId, records) : PickingLines.EMPTY;
        List<ErpOrderItemDTO> items = lines.items();
        // A picking belongs to one warehouse in Odoo, so every line ships from it. Stamping the value
        // here rather than leaving it null keeps the per-line depot resolution uniform across ERPs:
        // downstream never has to ask which provider it is talking to.
        final String pickingWarehouse = wh != null ? wh.code() : null;
        if (pickingWarehouse != null) {
            items.forEach(it -> { if (it.getWarehouseCode() == null) it.setWarehouseCode(pickingWarehouse); });
        }
        final BigDecimal collectable = lines.taxedValue();
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
                .source("ODOO")
                // The import identity. Mappable like the rest, but a tenant that repoints it
                // re-identifies its whole catalogue of orders — the mapping screen warns about that.
                .erpOrderId(mappedString(ERP_ORDER_ID, records, () -> asString(picking.get("name"))))
                .blNumber(mappedString(BL_NUMBER, records, () -> asString(picking.get("name"))))
                .saleOrderRef(mappedString(SALE_ORDER_REF, records,
                        () -> firstNonBlank(asRelName(picking.get("sale_id")), asString(picking.get("origin")))))
                .customerRef(mappedString(CUSTOMER_REF, records,
                        () -> saleRef != null ? asString(saleRef.get("client_order_ref")) : null))
                .warehouseCode(mappedString(WAREHOUSE_CODE, records, () -> wh != null ? wh.code() : null))
                .warehouseName(mappedString(WAREHOUSE_NAME, records, () -> wh != null ? wh.name() : null))
                .ready(mappedBoolean(READY, records, () -> ready))
                .customerName(mappedString(CUSTOMER_NAME, records,
                        () -> resolveCustomerName(saleRef, partnerRef, picking)))
                .customerPhone(mappedString(CUSTOMER_PHONE, records,
                        () -> partnerRef != null ? asString(partnerRef.get("phone")) : null))
                .deliveryAddress(mappedString(DELIVERY_ADDRESS, records, () -> buildAddress(partnerRef)))
                .deliveryCity(mappedString(DELIVERY_CITY, records,
                        () -> partnerRef != null ? asString(partnerRef.get("city")) : null))
                .deliveryPostalCode(mappedString(DELIVERY_POSTAL_CODE, records,
                        () -> partnerRef != null ? asString(partnerRef.get("zip")) : null))
                .deliveryInstructions(mappedString(DELIVERY_INSTRUCTIONS, records,
                        () -> saleRef != null ? asString(saleRef.get("note")) : null))
                .totalAmount(mappedDecimal(TOTAL_AMOUNT, records,
                        () -> saleRef != null ? asBigDecimal(saleRef.get("amount_total")) : null))
                .currency(mappedString(CURRENCY, records, () -> resolveCurrency(saleRef)))
                // Default false: see CanonicalField.COD_REQUIRED — with money the safe guess is
                // "collect nothing", and switching it on is a human decision.
                .codRequired(mappedBoolean(COD_REQUIRED, records, () -> false))
                // This delivery note's own taxed value, not the sale order's total — see taxedValueOf.
                .codAmount(mappedDecimal(COD_AMOUNT, records, () -> collectable))
                .priority(mappedString(PRIORITY, records, () -> "NORMAL"))
                // Whatever the integrator mapped that ASM has no field for — carried through so the
                // value is not silently read and dropped.
                .customFields(emptyToNull(fieldMapping.resolveCustomFields(records)))
                .dateOrder(mappedDateTime(DATE_ORDER, records,
                        () -> saleRef != null ? parseOdooDateTime(saleRef.get("date_order")) : null))
                .scheduledAt(mappedDateTime(SCHEDULED_AT, records,
                        () -> parseOdooDateTime(picking.get("scheduled_date"))))
                .items(items)
                .totalQuantity(totalQty)
                .totalWeightKg(totalWeight)
                .build();
    }

    // ── Warehouses (source depots) ────────────────────────────────────────────────

    @Override
    public List<ErpWarehouseDTO> getWarehouses() {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> warehouses = rpc.searchReadStrict("stock.warehouse",
                List.of(), List.of("id", "code", "name", "partner_id"), 0, "name asc");
        if (warehouses.isEmpty()) return List.of();

        // Resolve address partners in bulk (street/city/zip + coordinates when populated in Odoo).
        Set<Integer> partnerIds = relIds(warehouses, "partner_id");
        Map<Integer, Map<String, Object>> partners = partnerIds.isEmpty() ? Map.of()
                : rpc.searchReadStrict("res.partner",
                        List.of(List.of("id", "in", partnerIds.stream().toList())),
                        List.of("id", "street", "street2", "city", "zip",
                                "partner_latitude", "partner_longitude"),
                        partnerIds.size(), "id asc").stream()
                .filter(p -> asInt(p.get("id")) != null)
                .collect(Collectors.toMap(p -> asInt(p.get("id")), p -> p, (a, b) -> a));

        List<ErpWarehouseDTO> result = new ArrayList<>();
        for (Map<String, Object> w : warehouses) {
            String code = asString(w.get("code"));
            if (code == null) continue; // code is the stable key ASM maps to a depot
            Map<String, Object> partner = partners.get(asRelId(w.get("partner_id")));
            Double lat = partner != null ? asDouble(partner.get("partner_latitude")) : null;
            Double lng = partner != null ? asDouble(partner.get("partner_longitude")) : null;
            // Odoo stores 0.0 for "unset" coordinates — treat as missing so ASM can geocode.
            if (lat != null && lat == 0.0) lat = null;
            if (lng != null && lng == 0.0) lng = null;
            result.add(ErpWarehouseDTO.builder()
                    .erpWarehouseId(String.valueOf(asInt(w.get("id"))))
                    .code(code)
                    .name(firstNonBlank(asString(w.get("name")), code))
                    .address(buildAddress(partner))
                    .city(partner != null ? asString(partner.get("city")) : null)
                    .latitude(lat)
                    .longitude(lng)
                    .build());
        }

        log.info("getWarehouses count={} durationMs={}", result.size(), System.currentTimeMillis() - start);
        return result;
    }

    // ── Company (tenant's own selling entity) ─────────────────────────────────────

    @Override
    public ErpCompanyDTO getCompany() {
        List<Map<String, Object>> rows = rpc.searchReadStrict("res.company",
                List.of(),
                List.of("id", "name", "street", "street2", "city", "zip", "phone", "email", "vat", "website", "logo"),
                1, "id asc");
        if (rows.isEmpty()) return null;
        Map<String, Object> c = rows.get(0);
        String logoRaw = asString(c.get("logo"));
        return ErpCompanyDTO.builder()
                .name(asString(c.get("name")))
                .address(buildAddress(c))
                .city(asString(c.get("city")))
                .phone(asString(c.get("phone")))
                .email(asString(c.get("email")))
                .vat(asString(c.get("vat")))
                .website(asString(c.get("website")))
                .logo(logoRaw != null && !logoRaw.isBlank() ? logoRaw : null)
                .build();
    }

    // ── Picking ref by id (backorder linking) ─────────────────────────────────────

    @Override
    public String getPickingRef(String pickingId) {
        Integer id = asInt(pickingId);
        if (id == null) return null;
        List<Map<String, Object>> rows = rpc.searchReadStrict("stock.picking",
                List.of(List.of("id", "=", id)), List.of("id", "name"), 1, "id desc");
        return rows.isEmpty() ? null : asString(rows.get(0).get("name"));
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
                withMappedFields("stock.picking", PICKING_FIELDS), 1, "id desc");
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
                withMappedFields("res.partner",
                        List.of("id", "name", "phone", "street", "street2", "city", "zip")),
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
                withMappedFields("sale.order",
                        List.of("id", "name", "client_order_ref", "partner_id", "amount_total",
                                "currency_id", "payment_term_id", "note", "date_order")),
                saleIds.size(), "id asc");
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Integer id = asInt(r.get("id"));
            if (id != null) result.put(id, r);
        }
        return result;
    }

    /**
     * Line items actually shipped on this delivery note (the picking's stock moves).
     *
     * <p>Each line is resolved against its own scope — the move, its product, its sale line, plus the
     * header records — so a mapping like {@code x_lot} or {@code product_id.x_ref_client} reads from
     * the row the integrator was looking at. Without that, a per-line mapping would be evaluated
     * against the delivery note and quietly return nothing on every row.
     */
    private PickingLines fetchItemsFromPicking(int pickingId, Integer saleId,
                                               Map<String, Map<String, Object>> header) {
        List<Map<String, Object>> moves = rpc.searchReadStrict("stock.move",
                List.of(List.of("picking_id", "=", pickingId)),
                withMappedFields("stock.move", List.of("id", "product_id", "product_uom_qty")), 0, "id asc");
        if (moves.isEmpty()) return PickingLines.EMPTY;

        Set<Integer> productIds = moves.stream()
                .map(m -> asRelId(m.get("product_id"))).filter(Objects::nonNull).collect(Collectors.toSet());
        ProductDetails pd = fetchProductDetails(productIds);

        Map<Integer, BigDecimal> pricesByProduct = new HashMap<>();
        Map<Integer, Map<String, Object>> saleLinesByProduct = new HashMap<>();
        // Taxed value of one unit, kept apart from price_unit. price_unit is what the line displays;
        // this is what the customer owes for it. Conflating the two is how a collection instruction
        // ends up short by the VAT — and nobody notices until a driver comes back with too little.
        Map<Integer, BigDecimal> taxedUnitByProduct = new HashMap<>();
        if (saleId != null) {
            List<Map<String, Object>> saleLines = rpc.searchReadStrict("sale.order.line",
                    List.of(List.of("order_id", "=", saleId)),
                    withMappedFields("sale.order.line",
                            List.of("product_id", "price_unit", "price_total", "product_uom_qty")),
                    100, "id asc");
            for (Map<String, Object> sl : saleLines) {
                Integer pid = asRelId(sl.get("product_id"));
                BigDecimal price = asBigDecimal(sl.get("price_unit"));
                if (pid != null) {
                    saleLinesByProduct.putIfAbsent(pid, sl);
                    if (price != null) pricesByProduct.put(pid, price);

                    BigDecimal lineTotal = asBigDecimal(sl.get("price_total"));
                    Double lineQty = asDouble(sl.get("product_uom_qty"));
                    if (lineTotal != null && lineQty != null && lineQty > 0) {
                        taxedUnitByProduct.putIfAbsent(pid, lineTotal.divide(
                                BigDecimal.valueOf(lineQty), 6, java.math.RoundingMode.HALF_UP));
                    }
                }
            }
        }

        BigDecimal taxedValue = taxedValueOf(moves, taxedUnitByProduct);

        List<ErpOrderItemDTO> items = moves.stream().map(m -> {
            Integer productId = asRelId(m.get("product_id"));
            String name = firstNonBlank(asRelName(m.get("product_id")), "ERP Item");
            int qty = (int) Math.round(asDouble(m.get("product_uom_qty")) != null ? asDouble(m.get("product_uom_qty")) : 1d);
            BigDecimal unitWeight = productId != null ? pd.weights.getOrDefault(productId, BigDecimal.ZERO) : BigDecimal.ZERO;
            String sku  = productId != null ? pd.skus.get(productId)  : null;
            String type = productId != null ? pd.types.get(productId) : null;
            BigDecimal unitPrice = productId != null ? pricesByProduct.get(productId) : null;
            // The taxed twin of the line above, and the only one any money question may be answered
            // from. Not exposed as a mappable field: a tenant remapping ITEM_UNIT_PRICE is choosing
            // which figure to display, not authorising a driver to collect it.
            BigDecimal unitTtc = productId != null ? taxedUnitByProduct.get(productId) : null;

            Map<String, Map<String, Object>> lineRecords = lineScope(header, m,
                    productId != null ? pd.records.get(productId) : null,
                    productId != null ? saleLinesByProduct.get(productId) : null);

            return ErpOrderItemDTO.builder()
                    .name(mappedString(ITEM_NAME, lineRecords, () -> name))
                    .sku(mappedString(ITEM_SKU, lineRecords, () -> sku))
                    .quantity(mappedInt(ITEM_QUANTITY, lineRecords, () -> qty > 0 ? qty : 1))
                    .unitPrice(mappedDecimal(ITEM_UNIT_PRICE, lineRecords, () -> unitPrice))
                    .unitPriceTtc(unitTtc)
                    .unitWeightKg(mappedDecimal(ITEM_UNIT_WEIGHT_KG, lineRecords, () -> unitWeight))
                    .productType(mappedString(ITEM_PRODUCT_TYPE, lineRecords, () -> type))
                    .build();
        }).collect(Collectors.toList());

        return new PickingLines(items, taxedValue);
    }

    /**
     * The delivery note's lines, plus what those lines are worth taxed.
     *
     * <p>The value travels with the items rather than being recomputed from them: {@link ErpOrderItemDTO}
     * carries {@code price_unit}, which is untaxed, so anything derived from the DTOs would silently be
     * short by the VAT.
     */
    private record PickingLines(List<ErpOrderItemDTO> items, BigDecimal taxedValue) {
        static final PickingLines EMPTY = new PickingLines(List.of(), null);
    }

    /**
     * What this delivery note is worth, taxes included — the amount a driver would collect for it.
     *
     * <p>Computed from the note's own moves, prorated on each sale line: {@code price_total ÷ ordered
     * qty × qty on this note}. The previous default was the sale order's {@code amount_total}, which is
     * the whole order: on a partial delivery it instructed the driver to collect for goods still at the
     * depot, and then to collect the same total again on the backorder.
     *
     * <p>Returns {@code null} — no collectable amount — as soon as one line cannot be priced, rather
     * than summing what is known and passing off a partial figure as the total. A missing instruction
     * is caught at import (it logs and imports without collection); an under-stated one is discovered
     * by a driver at a customer's door.
     *
     * <p>Service lines that ship nothing (delivery charges) are not part of any move and so are not
     * counted. A tenant whose collection includes them maps {@code COD_AMOUNT} to its own field —
     * which is, in any case, what the mapping screen exists for.
     */
    // Package-private, not private: this is the only arithmetic in the adapter whose result is money a
    // driver will ask a customer for, and it was shipping untested. A test needs to reach it.
    static BigDecimal taxedValueOf(List<Map<String, Object>> moves,
                                   Map<Integer, BigDecimal> taxedUnitByProduct) {
        if (taxedUnitByProduct.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> m : moves) {
            Integer pid = asRelId(m.get("product_id"));
            BigDecimal unit = pid != null ? taxedUnitByProduct.get(pid) : null;
            Double qty = asDouble(m.get("product_uom_qty"));
            if (unit == null || qty == null) return null;
            sum = sum.add(unit.multiply(BigDecimal.valueOf(qty)));
        }
        return sum.signum() > 0 ? sum.setScale(3, java.math.RoundingMode.HALF_UP) : null;
    }

    /**
     * @param records the raw product rows, kept so a line mapping can address {@code product.product}
     *                directly instead of being limited to the three values extracted below
     */
    private record ProductDetails(Map<Integer, BigDecimal> weights, Map<Integer, String> skus,
                                  Map<Integer, String> types, Map<Integer, Map<String, Object>> records) {}

    private ProductDetails fetchProductDetails(Set<Integer> productIds) {
        if (productIds == null || productIds.isEmpty()) return new ProductDetails(Map.of(), Map.of(), Map.of(), Map.of());
        // Product type lives on `type` (consu/service/combo); `is_storable` flags stockable goods.
        List<Map<String, Object>> rows = rpc.searchReadStrict("product.product",
                List.of(List.of("id", "in", productIds.stream().toList())),
                withMappedFields("product.product", List.of("id", "weight", "default_code", "type")),
                productIds.size(), "id asc");
        Map<Integer, BigDecimal> weights = new HashMap<>();
        Map<Integer, String>     skus    = new HashMap<>();
        Map<Integer, String>     types   = new HashMap<>();
        Map<Integer, Map<String, Object>> records = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer id = asInt(row.get("id"));
            if (id == null) continue;
            records.put(id, row);
            weights.put(id, asBigDecimal(row.get("weight")));
            String dc = asString(row.get("default_code"));
            if (dc != null && !dc.isBlank()) skus.put(id, dc);
            String dt = asString(row.get("type"));
            if (dt != null && !dt.isBlank()) types.put(id, neutralType(dt));
        }
        return new ProductDetails(weights, skus, types, records);
    }

    /** Map lookup that tolerates a missing relation, which an immutable map does not. */
    private static <V> V lookup(Map<Integer, V> byId, Integer id) {
        return id == null ? null : byId.get(id);
    }

    private ErpPendingOrderSummaryDTO mapToSummary(Map<String, Object> picking,
                                                   Map<Integer, Warehouse> warehouses,
                                                   Map<Integer, Map<String, Object>> partners,
                                                   Map<Integer, Map<String, Object>> saleOrders) {
        String bl = asString(picking.get("name"));
        if (bl == null) return null;

        // A picking need not come from a sale order: one created by hand in Odoo — a manual
        // shipment, a replacement, goods sent back out — carries sale_id = false, and asRelId then
        // answers null. These maps are immutable, and an immutable map throws on a null key instead
        // of answering null. So one hand-made picking raised a NullPointerException inside the
        // stream and took the whole pending list with it: the import screen went empty while every
        // other delivery note was perfectly importable.
        //
        // Everything below already treats sale, partner and warehouse as optional. Only the lookup
        // was unsafe.
        Warehouse wh = lookup(warehouses, asRelId(picking.get("picking_type_id")));
        Map<String, Object> partner = lookup(partners, asRelId(picking.get("partner_id")));
        Map<String, Object> sale = lookup(saleOrders, asRelId(picking.get("sale_id")));

        final Map<String, Map<String, Object>> records = scope(picking, sale, partner);
        final Map<String, Object> saleRef = sale;
        final Map<String, Object> partnerRef = partner;

        return ErpPendingOrderSummaryDTO.builder()
                .erpOrderId(mappedString(ERP_ORDER_ID, records, () -> bl))   // import identity
                .blNumber(mappedString(BL_NUMBER, records, () -> bl))
                .saleOrderRef(mappedString(SALE_ORDER_REF, records,
                        () -> firstNonBlank(asRelName(picking.get("sale_id")), asString(picking.get("origin")))))
                .customerRef(mappedString(CUSTOMER_REF, records,
                        () -> saleRef != null ? asString(saleRef.get("client_order_ref")) : null))
                .warehouseCode(mappedString(WAREHOUSE_CODE, records, () -> wh != null ? wh.code() : null))
                .warehouseName(mappedString(WAREHOUSE_NAME, records, () -> wh != null ? wh.name() : null))
                .ready(mappedBoolean(READY, records, () -> "assigned".equals(asString(picking.get("state")))))
                .customerName(mappedString(CUSTOMER_NAME, records,
                        () -> resolveCustomerName(saleRef, partnerRef, picking)))
                .customerPhone(mappedString(CUSTOMER_PHONE, records,
                        () -> partnerRef != null ? asString(partnerRef.get("phone")) : null))
                .deliveryAddress(mappedString(DELIVERY_ADDRESS, records, () -> buildAddress(partnerRef)))
                .deliveryCity(mappedString(DELIVERY_CITY, records,
                        () -> partnerRef != null ? asString(partnerRef.get("city")) : null))
                .deliveryPostalCode(mappedString(DELIVERY_POSTAL_CODE, records,
                        () -> partnerRef != null ? asString(partnerRef.get("zip")) : null))
                .totalAmount(mappedDecimal(TOTAL_AMOUNT, records,
                        () -> saleRef != null ? asBigDecimal(saleRef.get("amount_total")) : null))
                .currency(mappedString(CURRENCY, records, () -> resolveCurrency(saleRef)))
                // Default false: see CanonicalField.COD_REQUIRED — with money the safe guess is
                // "collect nothing", and switching it on is a human decision.
                .codRequired(mappedBoolean(COD_REQUIRED, records, () -> false))
                // No default here. This is the import list, which reads delivery notes in bulk and does
                // not load their lines, so the collectable value cannot be computed — and the sale
                // order's amount_total, which used to stand in for it, would now contradict the figure
                // the preview computes and the import stores. A blank cell is honest; a number that
                // disagrees with the one imported a click later is not.
                .codAmount(mappedDecimal(COD_AMOUNT, records, () -> null))
                // backorder_id is set by Odoo when this picking is the remainder (reliquat) of a prior
                // partial delivery; surface it so the operator sees it's a backorder before importing.
                .priority(mappedString(PRIORITY, records, () -> "NORMAL"))
                .backorder(asRelId(picking.get("backorder_id")) != null)
                .originBl(asRelName(picking.get("backorder_id")))
                .dateOrder(mappedDateTime(DATE_ORDER, records,
                        () -> saleRef != null ? parseOdooDateTime(saleRef.get("date_order")) : null))
                .scheduledAt(mappedDateTime(SCHEDULED_AT, records,
                        () -> parseOdooDateTime(picking.get("scheduled_date"))))
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

    /**
     * Maps the ERP's raw product type to a vendor-neutral canonical value so the DTO never leaks
     * Odoo vocabulary. Odoo: product→STORABLE, consu→CONSUMABLE, service→SERVICE; anything else is
     * passed through upper-cased.
     */
    static String neutralType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "product" -> "STORABLE";
            case "consu"   -> "CONSUMABLE";
            case "service" -> "SERVICE";
            default        -> raw.trim().toUpperCase(Locale.ROOT);
        };
    }
}
