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

    // Cache to match original logic signature layout, though simplified here.
    private final ConcurrentHashMap<String, CacheEntry<List<ErpClientDTO>>> clientCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpProductDTO>>> productCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpPendingOrderSummaryDTO>>> pendingOrderCache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private String companyKey() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p && p.getCompanyId() != null)
            return p.getCompanyId();
        return "global";
    }

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

    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) {
        String cacheKey = companyKey() + "-pending-" + limit;
        CacheEntry<List<ErpPendingOrderSummaryDTO>> cached = pendingOrderCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.value();

        List<Map<String, Object>> res = erpAdapterClient.getPendingOrders(limit, null);
        List<ErpPendingOrderSummaryDTO> dtos = res.stream()
                .map(m -> objectMapper.convertValue(m, ErpPendingOrderSummaryDTO.class))
                .collect(Collectors.toList());

        for (ErpPendingOrderSummaryDTO dto : dtos) {
            if (dto.getErpOrderId() == null) continue;
            orderRepository.findByErpOrderId(dto.getErpOrderId()).ifPresent(o -> {
                dto.setAlreadyImported(true);
                dto.setExistingDeliveryId(deliveryRepository.findByOrderId(o.getId()).map(Delivery::getId).orElse(null));
                try {
                    dto.setExistingBackorderId(o.getOdooBackorderId());
                } catch (Exception e) {
                    dto.setExistingBackorderId(null);
                }
            });
        }

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

        Optional<Order> existingOrder = orderRepository.findByErpOrderId(erpOrderId);
        if (existingOrder.isPresent()) {
            throw AppException.badRequest("Order " + erpOrderId + " already imported");
        }

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
                .isCod("Immediate Payment".equalsIgnoreCase(preview.getPaymentTermName()))
                .scheduledAt(preview.getScheduledAt())
                .priority(OrderPriority.NORMAL)
                .items(new ArrayList<>())
                .totalQuantity(preview.getTotalQuantity() != null ? preview.getTotalQuantity() : 0)
                .totalWeightKg(preview.getTotalWeightKg() != null ? preview.getTotalWeightKg() : BigDecimal.ZERO)
                .status(OrderStatus.PENDING)
                .erpOrderId(preview.getErpOrderId())
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
                        .build();
                order.getItems().add(item);
            }
        }

        order = orderRepository.save(order);

        Delivery delivery = Delivery.builder()
                .order(order)
                .status(DeliveryStatus.UNSCHEDULED)
                .build();
        delivery = deliveryRepository.save(delivery);

        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(DeliveryStatus.UNSCHEDULED)
                .changedBy("SYSTEM")
                .changedByRole(Role.SYSTEM)
                .note("Imported from ERP via Adapter")
                .build());

        eventPublisher.publishDeliveryCreated(order, delivery);
        pendingOrderCache.clear();

        return toOrderResponse(order, delivery);
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
