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
import com.asm.delivery.erp.port.ErpPort;
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

    private final ErpPort erpPort;
    private final OrderRepository orderRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository historyRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.asm.delivery.repository.DepotRepository depotRepository;
    private final com.asm.delivery.service.OrderGeocodingService orderGeocodingService;

    // Cache to match original logic signature layout, though simplified here.
    private final ConcurrentHashMap<String, CacheEntry<List<ErpClientDTO>>> clientCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpProductDTO>>> productCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<ErpPendingOrderSummaryDTO>>> pendingOrderCache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    // Single-tenant: one instance = one ERP/company, so the lookup caches are global (no per-company key).
    private static final String CACHE_SCOPE = "global";

    @Transactional(readOnly = true)
    public List<ErpClientDTO> searchClients(String search, int limit) {
        String cacheKey = CACHE_SCOPE + "-clients-" + search + "-" + limit;
        CacheEntry<List<ErpClientDTO>> cached = clientCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.value();

        List<ErpClientDTO> dtos = erpPort.searchClients(search, limit);
        clientCache.put(cacheKey, new CacheEntry<>(dtos, System.currentTimeMillis()));
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<ErpProductDTO> searchProducts(String search, int limit) {
        String cacheKey = CACHE_SCOPE + "-products-" + search + "-" + limit;
        CacheEntry<List<ErpProductDTO>> cached = productCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.value();

        List<ErpProductDTO> dtos = erpPort.searchProducts(search, limit);
        productCache.put(cacheKey, new CacheEntry<>(dtos, System.currentTimeMillis()));
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit, boolean forceRefresh) {
        String cacheKey = CACHE_SCOPE + "-pending-" + limit;
        if (forceRefresh) pendingOrderCache.remove(cacheKey);
        CacheEntry<List<ErpPendingOrderSummaryDTO>> cached = pendingOrderCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.value();

        Set<String> importedErpIds = orderRepository.findAllErpOrderIds();
        Set<String> importedBls = orderRepository.findAllBlNumbers();

        List<ErpPendingOrderSummaryDTO> dtos = erpPort.getPendingOrders(limit).stream()
                .filter(dto -> dto.getErpOrderId() != null)
                // Mark (don't drop) already-imported orders — by delivery-note (BL) when
                // present, else by ERP order ref — so the UI's "Déjà importées" tab can
                // show them instead of the list silently excluding them.
                .map(dto -> {
                    boolean imported = StringUtils.hasText(dto.getBlNumber())
                            ? importedBls.contains(dto.getBlNumber())
                            : importedErpIds.contains(dto.getErpOrderId());
                    dto.setAlreadyImported(imported);
                    return dto;
                })
                .collect(Collectors.toList());

        pendingOrderCache.put(cacheKey, new CacheEntry<>(dtos, System.currentTimeMillis()));
        return dtos;
    }

    public ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId) {
        ErpPendingOrderPreviewDTO preview = erpPort.getPendingOrderPreview(erpOrderId);
        if (preview == null) {
            throw AppException.notFound("Pending order not found: " + erpOrderId);
        }
        return preview;
    }

    @Transactional
    public OrderResponse importPendingOrder(String erpOrderId) {
        ErpPendingOrderPreviewDTO preview = getPendingOrderPreview(erpOrderId);

        // Backorder-aware idempotency.
        // BL-articulated ERP (Odoo): each delivery attempt is a distinct picking with its own BL number,
        // so a repeat import is a true duplicate → block on the BL.
        // SO-articulated ERP (ERPNext): the SAME Sales Order re-appears for its remaining (backorder),
        // since ERPNext tracks delivered_qty on the SO instead of creating a new document. Allow the
        // reliquat once no prior attempt is still in progress and something is actually left to deliver;
        // give it a DISTINCT erpOrderId (the system relies on erpOrderId being unique) while
        // erpExternalRef stays the shared SO ref (used for sync + sibling grouping) — structurally the
        // same as Odoo, where erpOrderId=BL is distinct per attempt and erpExternalRef=SO is shared.
        String blNumber = preview.getBlNumber();
        String importErpOrderId = preview.getErpOrderId();
        if (StringUtils.hasText(blNumber)) {
            if (orderRepository.findByBlNumber(blNumber).isPresent()) {
                throw AppException.badRequest("Delivery note " + blNumber + " already imported");
            }
        } else {
            List<Order> priorForSo = orderRepository.findByErpExternalRefOrderByCreatedAtAsc(erpOrderId);
            if (!priorForSo.isEmpty()) {
                if (priorForSo.stream().anyMatch(this::hasActiveDelivery)) {
                    throw AppException.badRequest("Order " + erpOrderId + " already has a delivery in progress");
                }
                if (preview.getItems() == null || preview.getItems().isEmpty()) {
                    throw AppException.badRequest("Order " + erpOrderId + " has nothing left to deliver");
                }
                importErpOrderId = erpOrderId + "#R" + (priorForSo.size() + 1);   // backorder: distinct id, shared SO ref
            }
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

        // erpOrderId stores the BL/picking reference (e.g. WH/OUT/00131) for display.
        // erpExternalRef stores the sale-order reference (e.g. S00110) for ERP sync.
        // Sync services (cancellation, failure) need the sale order ref; the BL number
        // is passed separately as pickingRef to the adapter.
        String saleRef = StringUtils.hasText(preview.getSaleOrderRef()) ? preview.getSaleOrderRef() : null;

        Order order = Order.builder()
                .source(OrderSource.fromProvider(preview.getSource()))   // ERPNEXT / ODOO — the adapter tags it
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
                .scheduledAt(preview.getScheduledAt())
                .priority(OrderPriority.NORMAL)
                .items(new ArrayList<>())
                .totalQuantity(preview.getTotalQuantity() != null ? preview.getTotalQuantity() : 0)
                .totalWeightKg(preview.getTotalWeightKg() != null ? preview.getTotalWeightKg() : BigDecimal.ZERO)
                .status(OrderStatus.PENDING)
                .erpOrderId(importErpOrderId)                  // BL ref (Odoo) / SO or SO#Rn for a backorder (ERPNext)
                .erpExternalRef(saleRef)                       // S00110 — sale ref for sync
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

        // Group this shipment under its sale order: if other orders already share this saleRef
        // (the original + earlier backorder(s)/multi-depot splits), link the new order to the group
        // ROOT so the original PARTIALLY_DELIVERED order and its backorder are traceable together.
        // The first import of a sale order has no siblings → stays the root (parentOrderId == null).
        if (StringUtils.hasText(saleRef)) {
            List<Order> siblings = orderRepository.findByErpExternalRefOrderByCreatedAtAsc(saleRef);
            if (!siblings.isEmpty()) {
                UUID rootId = siblings.stream()
                        .filter(o -> o.getParentOrderId() == null)
                        .map(Order::getId)
                        .findFirst()
                        .orElse(siblings.get(0).getId());
                order.setParentOrderId(rootId);
            }
        }

        order = orderRepository.save(order);

        Delivery delivery = Delivery.builder()
                .order(order)
                .status(DeliveryStatus.UNSCHEDULED)
                .sourceDepotId(sourceDepotId)
                .build();
        delivery = deliveryRepository.save(delivery);

        // Stamp the REAL actor: an ERP import is triggered by an admin/dispatcher hitting the import
        // page — the audit must read "importé par {name}", not "Système". ActorContext resolves the
        // X-User-* headers of the current request; it falls back to SYSTEM for a headless webhook/poller.
        historyRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(DeliveryStatus.UNSCHEDULED)
                .changedBy(com.asm.delivery.web.ActorContext.changedBy())
                .changedByRole(com.asm.delivery.web.ActorContext.role())
                .eventKey("DELIVERY_CREATED")
                .eventParams("{}")
                .build());

        eventPublisher.publishDeliveryCreated(order, delivery);
        pendingOrderCache.clear();

        // Auto-geocode + auto-zone after the import commits (async, throttled). Best-effort:
        // failure leaves the order unpinned for the dispatcher to fix manually.
        scheduleGeocode(order.getId());

        return toOrderResponse(order, delivery);
    }

    /** Trigger async geocoding once the import transaction has committed (so the row is visible). */
    private void scheduleGeocode(UUID orderId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { orderGeocodingService.enrichOrderAsync(orderId); }
                });
        } else {
            orderGeocodingService.enrichOrderAsync(orderId);
        }
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

    public void invalidateCache() {
        clientCache.clear();
        productCache.clear();
        pendingOrderCache.clear();
    }

    /** True when the order's latest shipment is still being worked (not in a terminal state). */
    private boolean hasActiveDelivery(Order order) {
        return deliveryRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getId())
                .map(d -> !isTerminalDelivery(d.getStatus()))
                .orElse(false);
    }

    private static boolean isTerminalDelivery(DeliveryStatus s) {
        return s == DeliveryStatus.DELIVERED || s == DeliveryStatus.PARTIALLY_DELIVERED
                || s == DeliveryStatus.FAILED || s == DeliveryStatus.CANCELLED;
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
