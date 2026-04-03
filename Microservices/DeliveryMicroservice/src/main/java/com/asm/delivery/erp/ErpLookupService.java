package com.asm.delivery.erp;

import com.asm.delivery.dto.response.OrderResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.DeliveryStatusHistory;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.OrderPriority;
import com.asm.delivery.entity.OrderSource;
import com.asm.delivery.entity.OrderStatus;
import com.asm.delivery.entity.Role;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.odoo.OdooClient;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DeliveryStatusHistoryRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ErpLookupService {

    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long PENDING_CACHE_TTL_MILLIS = Duration.ofMinutes(2).toMillis();
    private static final BigDecimal RESIDUAL_EPSILON = new BigDecimal("0.0001");

    private final OdooClient odooClient;
    private final OrderRepository orderRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository historyRepository;
    private final EventPublisher eventPublisher;

    private final ConcurrentHashMap<String, CacheEntry<List<ErpClientDTO>>> clientCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpProductDTO>>> productCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpPendingOrderSummaryDTO>>> pendingOrderCache = new ConcurrentHashMap<>();

    public List<ErpClientDTO> searchClients(String search, int limit) {
        final String normalizedSearch = search.trim();
        final String cacheKey = "erp-clients-" + normalizedSearch.toLowerCase(Locale.ROOT) + "-" + limit;
        CacheEntry<List<ErpClientDTO>> cached = clientCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        long start = System.currentTimeMillis();
        try {
            List<Object> domain = List.of(
                    "|",
                    List.of("name", "ilike", normalizedSearch),
                    "|",
                    List.of("phone", "ilike", normalizedSearch),
                    List.of("mobile", "ilike", normalizedSearch),
                    List.of("customer_rank", ">", 0),
                    List.of("active", "=", true)
            );

            List<Map<String, Object>> rows = odooClient.searchRead(
                    "res.partner",
                    domain,
                    List.of("id", "name", "phone", "mobile", "email"),
                    limit,
                    "name asc"
            );

            List<ErpClientDTO> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Integer id = asInt(row.get("id"));
                if (id == null) continue;
                String phone = firstNonBlank(normalizeNullableString(row.get("phone")), normalizeNullableString(row.get("mobile")));
                result.add(ErpClientDTO.builder()
                        .erpClientId(String.valueOf(id))
                        .name(orEmpty(normalizeNullableString(row.get("name"))))
                        .phone(phone)
                        .email(normalizeNullableString(row.get("email")))
                        .build());
            }

            clientCache.put(cacheKey, new CacheEntry<>(result, System.currentTimeMillis() + CACHE_TTL_MILLIS));
            log.info("ERP client search term='{}' count={} durationMs={}", normalizedSearch, result.size(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("ERP client search failed for term='{}': {}", normalizedSearch, e.getMessage());
            return List.of();
        }
    }

    public List<ErpProductDTO> searchProducts(String search, int limit) {
        final String normalizedSearch = search.trim();
        final String cacheKey = "erp-products-" + normalizedSearch.toLowerCase(Locale.ROOT) + "-" + limit;
        CacheEntry<List<ErpProductDTO>> cached = productCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        long start = System.currentTimeMillis();
        try {
            List<Object> domain = List.of(
                    List.of("sale_ok", "=", true),
                    List.of("active", "=", true),
                    "|",
                    List.of("name", "ilike", normalizedSearch),
                    List.of("default_code", "ilike", normalizedSearch)
            );

            List<Map<String, Object>> rows = odooClient.searchRead(
                    "product.product",
                    domain,
                    List.of("id", "name", "default_code", "list_price", "qty_available", "description_sale", "weight"),
                    limit,
                    "name asc"
            );

            List<ErpProductDTO> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Integer id = asInt(row.get("id"));
                if (id == null) continue;

                int stock = asInt(row.get("qty_available")) != null ? asInt(row.get("qty_available")) : 0;
                double price = asDouble(row.get("list_price")) != null ? asDouble(row.get("list_price")) : 0.0;
                double weight = asDouble(row.get("weight")) != null ? asDouble(row.get("weight")) : 0.0;

                result.add(ErpProductDTO.builder()
                        .erpProductId(String.valueOf(id))
                        .name(orEmpty(normalizeNullableString(row.get("name"))))
                        .sku(normalizeNullableString(row.get("default_code")))
                        .price(price)
                        .stock(stock)
                        .available(stock > 0)
                        .description(normalizeNullableString(row.get("description_sale")))
                        .weightKg(weight)
                        .build());
            }

            productCache.put(cacheKey, new CacheEntry<>(result, System.currentTimeMillis() + CACHE_TTL_MILLIS));
            log.info("ERP product search term='{}' count={} durationMs={}", normalizedSearch, result.size(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("ERP product search failed for term='{}': {}", normalizedSearch, e.getMessage());
            return List.of();
        }
    }

    public void invalidateCache() {
        clientCache.clear();
        productCache.clear();
        pendingOrderCache.clear();
    }

    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) {
        final String cacheKey = "erp-pending-orders-" + limit;
        CacheEntry<List<ErpPendingOrderSummaryDTO>> cached = pendingOrderCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        long start = System.currentTimeMillis();
        try {
            List<Object> domain = List.of(
                List.of("state", "!=", "cancel")
            );

            List<Map<String, Object>> rows = odooClient.searchRead(
                    "sale.order",
                    domain,
                    List.of(
                            "id",
                            "name",
                            "client_order_ref",
                            "partner_id",
                            "partner_shipping_id",
                            "amount_total",
                            "currency_id",
                            "state",
                            "invoice_status",
                            "date_order",
                            "commitment_date"
                    ),
                    limit,
                    "date_order desc"
            );

            Map<Integer, Map<String, Object>> partners = fetchPartnersForOrders(rows);

            List<ErpPendingOrderSummaryDTO> summaries = rows.stream()
                    .map(row -> mapSaleOrderToSummary(row, partners))
                    .filter(item -> item != null && StringUtils.hasText(item.getErpOrderId()))
                    .sorted(Comparator.comparing(
                            ErpPendingOrderSummaryDTO::getDateOrder,
                            Comparator.nullsLast(Comparator.reverseOrder())
                    ))
                    .collect(Collectors.toList());

            pendingOrderCache.put(cacheKey, new CacheEntry<>(summaries, System.currentTimeMillis() + PENDING_CACHE_TTL_MILLIS));
            log.info("ERP pending orders fetched count={} durationMs={}", summaries.size(), System.currentTimeMillis() - start);
            return summaries;
        } catch (Exception e) {
            log.warn("ERP pending order fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    public ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId) {
        String normalized = normalizeNullableString(erpOrderId);
        if (!StringUtils.hasText(normalized)) {
            throw AppException.badRequest("ERP order id is required");
        }

        Optional<Order> existing = orderRepository.findByErpOrderId(normalized);
        if (existing.isPresent()) {
            Delivery existingDelivery = deliveryRepository.findByOrderId(existing.get().getId()).orElse(null);
            Order order = existing.get();
            return ErpPendingOrderPreviewDTO.builder()
                    .erpOrderId(order.getErpOrderId())
                    .externalRef(order.getErpExternalRef())
                    .source(OrderSource.ODOO.name())
                    .customerName(order.getClientName())
                    .customerPhone(order.getClientPhone())
                    .deliveryAddress(order.getDropoffAddress())
                    .deliveryCity(order.getDropoffCity())
                    .deliveryInstructions(order.getDeliveryInstructions())
                    .totalAmount(order.getTotalAmount())
                    .currency(order.getCurrency())
                    .priority(order.getPriority().name())
                    .dateOrder(order.getCreatedAt())
                    .scheduledAt(order.getScheduledAt())
                    .items(order.getItems())
                    .totalQuantity(order.getTotalQuantity())
                    .totalWeightKg(order.getTotalWeightKg())
                    .alreadyImported(true)
                    .existingDeliveryId(existingDelivery != null ? existingDelivery.getId() : null)
                    .existingBackorderId(order.getOdooBackorderId())
                    .build();
        }

        Map<String, Object> row = fetchSaleOrderByErpId(normalized);
        if (row == null) {
            throw AppException.notFound("ERP order not found");
        }

        Map<Integer, Map<String, Object>> partners = fetchPartnersForOrders(List.of(row));
        return mapSaleOrderToPreview(row, partners, false, null, null);
    }

    @Transactional
    public OrderResponse importPendingOrder(String erpOrderId) {
        String normalized = normalizeNullableString(erpOrderId);
        if (!StringUtils.hasText(normalized)) {
            throw AppException.badRequest("ERP order id is required");
        }

        Optional<Order> existingOrder = orderRepository.findByErpOrderId(normalized);
        if (existingOrder.isPresent()) {
            throw AppException.conflict("Order already imported");
        }

        Map<String, Object> row = fetchSaleOrderByErpId(normalized);
        if (row == null) {
            throw AppException.notFound("ERP order not found");
        }

        Map<Integer, Map<String, Object>> partners = fetchPartnersForOrders(List.of(row));
        ErpPendingOrderPreviewDTO preview = mapSaleOrderToPreview(row, partners, false, null, null);

        Order order = Order.builder()
                .source(OrderSource.ODOO)
                .schemaVersion("1.0.0")
                .clientId(null)
                .clientName(orFallback(preview.getCustomerName(), "ERP Customer"))
                .clientPhone(preview.getCustomerPhone())
                .clientEmail(null)
                .erpOrderId(preview.getErpOrderId())
                .erpExternalRef(preview.getExternalRef())
                .originName("ERP Warehouse")
                .originAddress(null)
                .originCity(null)
                .originPostalCode(null)
                .originCountryCode("TN")
                .originContactName(null)
                .originContactPhone(null)
                .originContactEmail(null)
                .dropoffAddress(orFallback(preview.getDeliveryAddress(), "Address not provided"))
                .dropoffCity(preview.getDeliveryCity())
                .dropoffPostalCode(null)
                .dropoffCountryCode("TN")
                .dropoffLat(null)
                .dropoffLng(null)
                .deliveryInstructions(preview.getDeliveryInstructions())
                .totalAmount(nonNullMoney(preview.getTotalAmount()))
                .currency(orFallback(preview.getCurrency(), "TND"))
                .scheduledAt(preview.getScheduledAt())
                .priority(parsePriority(preview.getPriority()))
                .items(preview.getItems() != null ? preview.getItems() : List.of())
                .totalQuantity(preview.getTotalQuantity() != null ? preview.getTotalQuantity() : 0)
                .totalWeightKg(preview.getTotalWeightKg() != null ? preview.getTotalWeightKg() : BigDecimal.ZERO)
                .status(OrderStatus.PENDING)
                .build();

        order = orderRepository.save(order);

        Delivery delivery = Delivery.builder()
                .order(order)
                .status(DeliveryStatus.WAITING_DRIVER)
                .build();
        delivery = deliveryRepository.save(delivery);

        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(DeliveryStatus.WAITING_DRIVER)
                .changedBy("SYSTEM")
                .changedByRole(Role.SYSTEM)
                .note("Imported from ERP")
                .build());

        eventPublisher.publishDeliveryCreated(order, delivery);
        pendingOrderCache.clear();

        return toOrderResponse(order, delivery);
    }

    public ErpMapOrderResponse createMapReadyOrder(CreateErpMapOrderRequest request) {
        String customerName = request.getCustomerName().trim();
        String customerPhone = request.getCustomerPhone().trim();

        Integer partnerId = odooClient.searchPartnerByPhone(customerPhone);
        if (partnerId == null) {
            partnerId = odooClient.createPartner(customerName, customerPhone);
        }
        if (partnerId == null) {
            throw AppException.badRequest("Failed to create or resolve Odoo partner");
        }

        boolean partnerUpdated = odooClient.updatePartnerMapFields(
                partnerId,
                request.getStreet(),
                request.getStreet2(),
                request.getCity(),
                request.getZip(),
                request.getLatitude(),
                request.getLongitude()
        );
        if (!partnerUpdated) {
            throw AppException.badRequest("Failed to update Odoo partner address");
        }

        OrderItem line = OrderItem.builder()
                .name(request.getItemName().trim())
                .quantity(request.getQuantity())
                .quantityDone(0)
                .unitPrice(request.getUnitPrice())
                .unitWeightKg(BigDecimal.ZERO)
                .build();

        StringBuilder note = new StringBuilder("Map-ready order");
        if (StringUtils.hasText(request.getNote())) {
            note.append(" | ").append(request.getNote().trim());
        }
        if (request.getLatitude() != null && request.getLongitude() != null) {
            note.append(" | lat=").append(request.getLatitude())
                    .append(" lng=").append(request.getLongitude());
        }

        Integer saleOrderId = odooClient.createSaleOrder(partnerId, List.of(line), note.toString());
        if (saleOrderId == null) {
            throw AppException.badRequest("Failed to create Odoo sale order");
        }

        String erpOrderId = odooClient.getSaleOrderReference(saleOrderId);
        pendingOrderCache.clear();

        return ErpMapOrderResponse.builder()
                .saleOrderId(saleOrderId)
                .erpOrderId(erpOrderId)
                .partnerId(partnerId)
                .customerName(customerName)
                .customerPhone(customerPhone)
                .street(request.getStreet())
                .city(request.getCity())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .build();
    }

    private ErpPendingOrderSummaryDTO mapSaleOrderToSummary(
            Map<String, Object> row,
            Map<Integer, Map<String, Object>> partners
    ) {
        String erpOrderId = normalizeNullableString(row.get("name"));
        if (!StringUtils.hasText(erpOrderId)) {
            return null;
        }

        Optional<Order> existingOrder = orderRepository.findByErpOrderId(erpOrderId);
        UUID deliveryId = existingOrder
                .flatMap(order -> deliveryRepository.findByOrderId(order.getId()).map(Delivery::getId))
                .orElse(null);

        Map<String, Object> addressPartner = resolveAddressPartner(row, partners);
        String address = buildAddress(addressPartner);
        String city = normalizeNullableString(addressPartner != null ? addressPartner.get("city") : null);

        BigDecimal totalAmount = asBigDecimal(row.get("amount_total"));

        return ErpPendingOrderSummaryDTO.builder()
                .erpOrderId(erpOrderId)
                .externalRef(normalizeNullableString(row.get("client_order_ref")))
                .customerName(resolveCustomerName(row, addressPartner))
                .customerPhone(resolveCustomerPhone(row, addressPartner))
                .deliveryAddress(address)
                .deliveryCity(city)
                .totalAmount(totalAmount)
                .currency(resolveCurrency(row))
                .state(normalizeNullableString(row.get("state")))
                .invoiceStatus(normalizeNullableString(row.get("invoice_status")))
                .dateOrder(parseOdooDateTime(row.get("date_order")))
                .scheduledAt(parseOdooDateTime(row.get("commitment_date")))
                .alreadyImported(existingOrder.isPresent())
                .existingDeliveryId(deliveryId)
                .existingBackorderId(existingOrder.map(Order::getOdooBackorderId).orElse(null))
                .build();
    }

    private ErpPendingOrderPreviewDTO mapSaleOrderToPreview(
            Map<String, Object> row,
            Map<Integer, Map<String, Object>> partners,
            boolean alreadyImported,
            UUID existingDeliveryId,
            Integer existingBackorderId
    ) {
        Map<String, Object> addressPartner = resolveAddressPartner(row, partners);
        List<OrderItem> items = fetchOrderItems(row);

        BigDecimal totalAmount = asBigDecimal(row.get("amount_total"));

        int totalQty = items.stream()
                .map(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .reduce(0, Integer::sum);

        BigDecimal totalWeightKg = items.stream()
            .map(item -> {
                BigDecimal unitWeight = item.getUnitWeightKg() != null ? item.getUnitWeightKg() : BigDecimal.ZERO;
                int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
                return unitWeight.multiply(BigDecimal.valueOf(quantity));
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ErpPendingOrderPreviewDTO.builder()
                .erpOrderId(normalizeNullableString(row.get("name")))
                .externalRef(normalizeNullableString(row.get("client_order_ref")))
                .source(OrderSource.ODOO.name())
                .customerName(resolveCustomerName(row, addressPartner))
                .customerPhone(resolveCustomerPhone(row, addressPartner))
                .deliveryAddress(buildAddress(addressPartner))
                .deliveryCity(normalizeNullableString(addressPartner != null ? addressPartner.get("city") : null))
                .deliveryInstructions(normalizeNullableString(row.get("note")))
                .totalAmount(totalAmount)
                .currency(resolveCurrency(row))
                .priority(OrderPriority.NORMAL.name())
                .dateOrder(parseOdooDateTime(row.get("date_order")))
                .scheduledAt(parseOdooDateTime(row.get("commitment_date")))
                .items(items)
                .totalQuantity(totalQty)
                .totalWeightKg(totalWeightKg)
                .alreadyImported(alreadyImported)
                .existingDeliveryId(existingDeliveryId)
                .existingBackorderId(existingBackorderId)
                .build();
    }

    private Map<Integer, Map<String, Object>> fetchPartnersForOrders(List<Map<String, Object>> rows) {
        Set<Integer> partnerIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Integer shippingId = asRelId(row.get("partner_shipping_id"));
            Integer partnerId = asRelId(row.get("partner_id"));
            if (shippingId != null) partnerIds.add(shippingId);
            if (partnerId != null) partnerIds.add(partnerId);
        }

        if (partnerIds.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> partnerRows = odooClient.searchRead(
                "res.partner",
                List.of(List.of("id", "in", partnerIds.stream().toList())),
                List.of("id", "name", "phone", "mobile", "street", "street2", "city", "zip"),
                Math.max(partnerIds.size(), 1),
                "id asc"
        );

        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> partnerRow : partnerRows) {
            Integer id = asInt(partnerRow.get("id"));
            if (id != null) {
                result.put(id, partnerRow);
            }
        }
        return result;
    }

    private Map<String, Object> fetchSaleOrderByErpId(String erpOrderId) {
        List<Map<String, Object>> rows = odooClient.searchRead(
                "sale.order",
                List.of(List.of("name", "=", erpOrderId)),
                List.of(
                        "id",
                        "name",
                        "client_order_ref",
                        "partner_id",
                        "partner_shipping_id",
                        "amount_total",
                        "currency_id",
                        "state",
                        "invoice_status",
                        "date_order",
                        "commitment_date",
                        "note",
                        "order_line",
                        "invoice_ids"
                ),
                1,
                "id desc"
        );

        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    private List<OrderItem> fetchOrderItems(Map<String, Object> saleOrderRow) {
        List<Integer> lineIds = asIdList(saleOrderRow.get("order_line"));
        if (lineIds.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> lineRows = odooClient.searchRead(
                "sale.order.line",
                List.of(List.of("id", "in", lineIds)),
                List.of("id", "product_id", "name", "product_uom_qty", "qty_delivered", "price_unit"),
                Math.max(lineIds.size(), 1),
                "id asc"
        );

        Set<Integer> productIds = lineRows.stream()
                .map(line -> asRelId(line.get("product_id")))
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<Integer, BigDecimal> productWeights = fetchProductWeights(productIds);

        return lineRows.stream()
                .map(line -> mapLineToOrderItem(line, productWeights))
                .collect(Collectors.toList());
    }

    private Map<Integer, BigDecimal> fetchProductWeights(Set<Integer> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> productRows = odooClient.searchRead(
                "product.product",
                List.of(List.of("id", "in", productIds.stream().toList())),
                List.of("id", "weight"),
                Math.max(productIds.size(), 1),
                "id asc"
        );

        Map<Integer, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : productRows) {
            Integer productId = asInt(row.get("id"));
            if (productId == null) {
                continue;
            }
            result.put(productId, asBigDecimal(row.get("weight")));
        }
        return result;
    }

    private OrderItem mapLineToOrderItem(Map<String, Object> line, Map<Integer, BigDecimal> productWeights) {
        Integer lineId = asInt(line.get("id"));
        Integer productId = asRelId(line.get("product_id"));
        String productName = asRelName(line.get("product_id"));
        String name = firstNonBlank(normalizeNullableString(line.get("name")), productName, "ERP Item");

        Integer qty = asInt(line.get("product_uom_qty"));
        Integer qtyDone = asInt(line.get("qty_delivered"));
        BigDecimal unitWeight = productId != null && productWeights != null
            ? productWeights.getOrDefault(productId, BigDecimal.ZERO)
            : BigDecimal.ZERO;

        return OrderItem.builder()
                .id(lineId != null ? String.valueOf(lineId) : null)
                .sku(productId != null ? String.valueOf(productId) : null)
                .name(name)
                .quantity(qty != null && qty > 0 ? qty : 1)
                .quantityDone(qtyDone != null ? Math.max(qtyDone, 0) : 0)
                .unitPrice(asBigDecimal(line.get("price_unit")))
            .unitWeightKg(unitWeight)
                .build();
    }

    private Map<String, Object> resolveAddressPartner(
            Map<String, Object> saleOrder,
            Map<Integer, Map<String, Object>> partners
    ) {
        Integer shippingId = asRelId(saleOrder.get("partner_shipping_id"));
        Integer partnerId = asRelId(saleOrder.get("partner_id"));
        if (shippingId != null && partners.containsKey(shippingId)) {
            return partners.get(shippingId);
        }
        if (partnerId != null) {
            return partners.get(partnerId);
        }
        return null;
    }

    private String resolveCustomerName(Map<String, Object> saleOrder, Map<String, Object> partner) {
        String partnerName = asRelName(saleOrder.get("partner_id"));
        String shippingName = partner != null ? normalizeNullableString(partner.get("name")) : null;
        return firstNonBlank(shippingName, partnerName, "ERP Customer");
    }

    private String resolveCustomerPhone(Map<String, Object> saleOrder, Map<String, Object> partner) {
        String fromPartner = partner != null
                ? firstNonBlank(normalizeNullableString(partner.get("phone")), normalizeNullableString(partner.get("mobile")))
                : null;
        return firstNonBlank(fromPartner);
    }

    private String resolveCurrency(Map<String, Object> saleOrder) {
        String raw = asRelName(saleOrder.get("currency_id"));
        if (!StringUtils.hasText(raw)) {
            return "TND";
        }
        if (raw.length() >= 3) {
            return raw.substring(0, 3).toUpperCase(Locale.ROOT);
        }
        return "TND";
    }

    private static String buildAddress(Map<String, Object> partner) {
        if (partner == null) return null;

        String street = normalizeNullableString(partner.get("street"));
        String street2 = normalizeNullableString(partner.get("street2"));
        String zip = normalizeNullableString(partner.get("zip"));

        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(street)) parts.add(street);
        if (StringUtils.hasText(street2)) parts.add(street2);
        if (StringUtils.hasText(zip)) parts.add(zip);

        if (parts.isEmpty()) {
            return null;
        }
        return String.join(", ", parts);
    }

    private static Integer asRelId(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof List<?> rel && !rel.isEmpty() && rel.get(0) instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static String asRelName(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof List<?> rel && rel.size() > 1 && rel.get(1) != null) {
            return normalizeNullableString(rel.get(1));
        }
        return null;
    }

    private static List<Integer> asIdList(Object value) {
        if (!(value instanceof Collection<?> c)) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (Object raw : c) {
            Integer id = asInt(raw);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static LocalDateTime parseOdooDateTime(Object value) {
        String text = normalizeNullableString(value);
        if (!StringUtils.hasText(text)) return null;

        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(text.replace("Z", ""));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof Boolean b && !b) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal nonNullMoney(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String orFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static OrderPriority parsePriority(String value) {
        if (!StringUtils.hasText(value)) return OrderPriority.NORMAL;
        try {
            return OrderPriority.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return OrderPriority.NORMAL;
        }
    }

    private static OrderResponse toOrderResponse(Order order, Delivery delivery) {
        return OrderResponse.builder()
                .id(order.getId())
                .source(order.getSource().name())
                .clientId(order.getClientId())
                .clientName(order.getClientName())
                .clientPhone(order.getClientPhone())
                .dropoffAddress(order.getDropoffAddress())
                .dropoffCity(order.getDropoffCity())
                .dropoffLat(order.getDropoffLat())
                .dropoffLng(order.getDropoffLng())
                .deliveryInstructions(order.getDeliveryInstructions())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .priority(order.getPriority().name())
                .scheduledAt(order.getScheduledAt())
                .items(order.getItems())
                .totalQuantity(order.getTotalQuantity())
                .totalWeightKg(order.getTotalWeightKg())
                .status(order.getStatus().name())
                .deliveryId(delivery != null ? delivery.getId() : null)
                .deliveryStatus(delivery != null ? delivery.getStatus().name() : null)
                .erpOrderId(order.getErpOrderId())
                .odooSyncStatus(order.getOdooSyncStatus())
                .odooBackorderId(order.getOdooBackorderId())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    private static String normalizeNullableString(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        String asString = String.valueOf(value).trim();
        return asString.isEmpty() ? null : asString;
    }

    private static Integer asInt(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b && !b) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    private static String firstNonBlank(String first, String second, String third) {
        String x = firstNonBlank(first, second);
        if (x != null && !x.isBlank()) return x;
        if (third != null && !third.isBlank()) return third;
        return null;
    }

    private static String firstNonBlank(String first) {
        if (first != null && !first.isBlank()) return first;
        return null;
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    private record CacheEntry<T>(T value, long expiresAtMillis) {
        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }
}
