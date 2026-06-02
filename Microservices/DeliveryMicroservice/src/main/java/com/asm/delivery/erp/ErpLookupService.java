package com.asm.delivery.erp;

import com.asm.delivery.dto.response.OrderResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.DeliveryStatusHistory;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.OrderPriority;
import com.asm.delivery.entity.OrderSource;
import com.asm.delivery.entity.OrderStatus;
import com.asm.delivery.entity.Role;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.erp.client.ErpAdapterClient;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DeliveryStatusHistoryRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.service.EventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    private final ErpAdapterClient erpAdapterClient;
    private final OrderRepository orderRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository historyRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.asm.delivery.repository.DepotRepository depotRepository;

    // Cache to match original logic signature layout, though simplified here.
    private final ConcurrentHashMap<String, CacheEntry<List<ErpClientDTO>>> clientCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpProductDTO>>> productCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpPendingOrderSummaryDTO>>> pendingOrderCache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private String companyKey() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();        return "global";
    }

    @Transactional(readOnly = true)
    public List<ErpClientDTO> searchClients(String search, int limit) {
        String cacheKey = companyKey() + "-clients-" + search + "-" + limit;
        CacheEntry<List<ErpClientDTO>> cached = clientCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.value();

        List<Map<String, Object>> res = erpAdapterClient.searchClients(search, limit, null);
        List<ErpClientDTO> dtos = res.stream()
                .map(m -> objectMapper.convertValue(m, ErpClientDTO.class))
                .collect(Collectors.toList());
        clientCache.put(cacheKey, new CacheEntry<>(dtos, System.currentTimeMillis()));
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<ErpProductDTO> searchProducts(String search, int limit) {
        String cacheKey = companyKey() + "-products-" + search + "-" + limit;
        CacheEntry<List<ErpProductDTO>> cached = productCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.value();

        List<Map<String, Object>> res = erpAdapterClient.searchProducts(search, limit, null);
        List<ErpProductDTO> dtos = res.stream()
                .map(m -> objectMapper.convertValue(m, ErpProductDTO.class))
                .collect(Collectors.toList());
        productCache.put(cacheKey, new CacheEntry<>(dtos, System.currentTimeMillis()));
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit, boolean forceRefresh) {
        String cacheKey = companyKey() + "-pending-" + limit;
        if (forceRefresh) pendingOrderCache.remove(cacheKey);
        CacheEntry<List<ErpPendingOrderSummaryDTO>> cached = pendingOrderCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.value();

        Set<String> importedErpIds = orderRepository.findAllErpOrderIds();
        Set<String> importedBls = orderRepository.findAllBlNumbers();

        List<Map<String, Object>> res = erpAdapterClient.getPendingOrders(limit, null);
        List<ErpPendingOrderSummaryDTO> dtos = res.stream()
                .map(m -> objectMapper.convertValue(m, ErpPendingOrderSummaryDTO.class))
                // Dedup per delivery-note (BL) when present, else per ERP order ref.
                .filter(dto -> dto.getErpOrderId() != null)
                .filter(dto -> StringUtils.hasText(dto.getBlNumber())
                        ? !importedBls.contains(dto.getBlNumber())
                        : !importedErpIds.contains(dto.getErpOrderId()))
                .collect(Collectors.toList());

        pendingOrderCache.put(cacheKey, new CacheEntry<>(dtos, System.currentTimeMillis()));
        return dtos;
    }

    public ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId) {
        Map<String, Object> preview = erpAdapterClient.getPendingOrderPreview(erpOrderId, null);
        if (preview == null || preview.isEmpty()) {
            throw AppException.notFound("Pending order not found: " + erpOrderId);
        }
        return objectMapper.convertValue(preview, ErpPendingOrderPreviewDTO.class);
    }

    @Transactional
    public OrderResponse importPendingOrder(String erpOrderId) {
        ErpPendingOrderPreviewDTO preview = getPendingOrderPreview(erpOrderId);

        // Idempotency: per delivery-note (BL) when available, else per ERP order ref.
        String blNumber = preview.getBlNumber();
        if (StringUtils.hasText(blNumber)) {
            if (orderRepository.findByBlNumber(blNumber).isPresent()) {
                throw AppException.badRequest("Delivery note " + blNumber + " already imported");
            }
        } else if (orderRepository.findByErpOrderId(erpOrderId).isPresent()) {
            throw AppException.badRequest("Order " + erpOrderId + " already imported");
        }

        // Resolve the source depot from the delivery-note warehouse. Depots mirror ERP
        // warehouses 1:1 (by code); if the warehouse isn't synced yet, leave null and let
        // the dispatcher resolve it by syncing depots — never block the import.
        String warehouseCode = preview.getWarehouseCode();
        UUID sourceDepotId = null;
        if (StringUtils.hasText(warehouseCode)) {
            sourceDepotId = depotRepository.findByWarehouseCode(warehouseCode)
                    .map(com.asm.delivery.entity.Depot::getId)
                    .orElse(null);
            if (sourceDepotId == null) {
                log.warn("Import bl={} : warehouse '{}' has no synced depot — run depot sync from ERP to resolve the source depot",
                        blNumber, warehouseCode);
            }
        }

        // Keep erpOrderId = the sale-order reference (cancel/failure sync act on the sale order);
        // the BL number is tracked separately and drives delivery/partial sync (per-picking).
        String saleRef = StringUtils.hasText(preview.getSaleOrderRef()) ? preview.getSaleOrderRef() : preview.getErpOrderId();

        Order order = Order.builder()
                .source(OrderSource.ODOO)
                .clientId(null)
                .erpClientId(null)

                .clientName(preview.getCustomerName())
                .clientPhone(preview.getCustomerPhone())
                .dropoffAddress(StringUtils.hasText(preview.getDeliveryAddress()) ? preview.getDeliveryAddress() : "Address not provided")
                .dropoffCity(preview.getDeliveryCity())
                .dropoffCountryCode("TN")
                .deliveryInstructions(preview.getDeliveryInstructions())
                .totalAmount(preview.getTotalAmount() != null ? preview.getTotalAmount() : BigDecimal.ZERO)
                .currency(StringUtils.hasText(preview.getCurrency()) ? preview.getCurrency() : "TND")
                .isCod(isImmediatePayment(preview.getPaymentTermName()))
                .scheduledAt(preview.getScheduledAt())
                .priority(OrderPriority.NORMAL)
                .items(new ArrayList<>())
                .totalQuantity(preview.getTotalQuantity() != null ? preview.getTotalQuantity() : 0)
                .totalWeightKg(preview.getTotalWeightKg() != null ? preview.getTotalWeightKg() : BigDecimal.ZERO)
                .status(OrderStatus.PENDING)
                .erpOrderId(saleRef)
                .erpExternalRef(preview.getExternalRef())
                .blNumber(blNumber)
                .warehouseCode(warehouseCode)
                .sourceDepotId(sourceDepotId)
                .build();

        if (preview.getItems() != null) {
            for (OrderItem previewItem : preview.getItems()) {
                OrderItem item = OrderItem.builder()
                        .sku(previewItem.getSku())
                        .name(previewItem.getName())
                        .quantity(previewItem.getQuantity())
                        .quantityDone(0)
                        .unitPrice(previewItem.getUnitPrice())
                        .unitWeightKg(previewItem.getUnitWeightKg())
                        .productType(previewItem.getProductType())
                        .build();
                order.getItems().add(item);
            }
        }

        order = orderRepository.save(order);

        Delivery delivery = Delivery.builder()
                .order(order)
                .status(DeliveryStatus.UNSCHEDULED)
                .sourceDepotId(sourceDepotId)
                .build();
        delivery = deliveryRepository.save(delivery);

        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(DeliveryStatus.UNSCHEDULED)
                .changedBy("SYSTEM")
                .changedByRole(Role.SYSTEM)
                .eventKey("DELIVERY_CREATED")
                .eventParams("{}")
                .build());

        eventPublisher.publishDeliveryCreated(order, delivery);
        pendingOrderCache.clear();

        return toOrderResponse(order, delivery);
    }


    @Transactional
    public Map<String, Object> bulkImportOrders(List<String> erpOrderIds) {
        int imported = 0, skipped = 0;
        for (String id : erpOrderIds) {
            try {
                importPendingOrder(id);
                imported++;
            } catch (Exception e) {
                log.warn("Bulk import: skipping {} — {}", id, e.getMessage());
                skipped++;
            }
        }
        return Map.of("imported", imported, "skipped", skipped, "requested", erpOrderIds.size());
    }

    /** Matches Odoo payment terms that mean "pay now" in any language/variant. */
    private static boolean isImmediatePayment(String termName) {
        if (termName == null || termName.isBlank()) return false;
        String t = termName.toLowerCase(java.util.Locale.ROOT);
        return t.contains("immediate") || t.contains("immédiat") || t.contains("paiement immédiat")
                || t.equals("now") || t.contains("cash on delivery") || t.contains("comptant");
    }

    public void invalidateCache() {
        clientCache.clear();
        productCache.clear();
        pendingOrderCache.clear();
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

    private record CacheEntry<T>(T value, long timestamp) {
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MILLIS;
        }
    }
}
