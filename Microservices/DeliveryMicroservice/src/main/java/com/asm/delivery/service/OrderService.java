package com.asm.delivery.service;

import com.asm.delivery.config.TenantContext;
import com.asm.delivery.dto.canonical.CanonicalDelivery;
import com.asm.delivery.dto.request.CreateOrderRequest;
import com.asm.delivery.dto.response.CancellableResponse;
import com.asm.delivery.dto.response.OrderResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.erp.ErpLookupService;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.erp.ErpSyncService;
import com.asm.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.asm.delivery.security.UserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository    orderRepo;
    private final DeliveryRepository deliveryRepo;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final EventPublisher     eventPublisher;
    private final OutboxProcessor    outboxProcessor;
    private final ErpLookupService   erpLookupService;
    private final AuditLogService    auditLogService;
    private final com.asm.delivery.repository.RouteStopRepository routeStopRepository;
    private final com.asm.delivery.repository.RouteRepository routeRepository;
    private final com.asm.delivery.service.route.RoutePlanningService routePlanningService;
    private final com.asm.delivery.service.route.RouteWebSocketService routeWebSocketService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${app.origin.name:Main Warehouse}")
    private String originName;
    @Value("${app.origin.address:123 Logistics Street}")
    private String originAddress;
    @Value("${app.origin.city:Tunis}")
    private String originCity;
    @Value("${app.origin.postal-code:1000}")
    private String originPostalCode;
    @Value("${app.origin.country-code:TN}")
    private String originCountryCode;

    private static final List<OrderStatus> TERMINAL = List.of(OrderStatus.CANCELLED, OrderStatus.DELIVERED, OrderStatus.PARTIALLY_DELIVERED);

    // ── Client REST entry point ───────────────────────────────────────────────

    @Transactional
    public OrderResponse createFromApp(CreateOrderRequest req, String clientId, String clientName, String clientPhone) {
        // Build items and calculate totals
        List<OrderItem> items = req.getItems() != null ? req.getItems() : List.of();
        int totalQty = items.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();
        BigDecimal totalWeight = items.stream()
                .map(i -> {
                    BigDecimal w = i.getUnitWeightKg() != null ? i.getUnitWeightKg() : BigDecimal.ZERO;
                    int q = i.getQuantity() != null ? i.getQuantity() : 0;
                    return w.multiply(BigDecimal.valueOf(q));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Assign IDs to items that lack them
        items.forEach(item -> {
            if (!StringUtils.hasText(item.getId())) item.setId(UUID.randomUUID().toString());
            if (item.getQuantityDone() == null) item.setQuantityDone(0);
        });

        UUID companyId = TenantContext.get() != null ? UUID.fromString(TenantContext.get()) : null;
        Order order = Order.builder()
            .source(OrderSource.APP)
                .clientId(clientId)
                .clientName(clientName)
                .clientPhone(clientPhone)
                .originName(originName)
                .originAddress(originAddress)
                .originCity(originCity)
                .originPostalCode(originPostalCode)
                .originCountryCode(originCountryCode)
                .dropoffAddress(req.getDropoffAddress())
                .dropoffCity(req.getDropoffCity())
                .dropoffPostalCode(req.getDropoffPostalCode())
                .dropoffCountryCode("TN")
                .dropoffLat(req.getDropoffLat())
                .dropoffLng(req.getDropoffLng())
                .deliveryInstructions(req.getDeliveryInstructions())
                .totalAmount(req.getTotalAmount())
                .currency("TND")
                .scheduledAt(parseDateTime(req.getScheduledAt()))
                .priority(req.getPriority() != null ? req.getPriority() : OrderPriority.NORMAL)
                .items(items)
                .totalQuantity(totalQty)
                .totalWeightKg(totalWeight)
                .status(OrderStatus.PENDING)
                .companyId(companyId)
                .build();

        order = orderRepo.save(order);

        auditLogService.logAction(null, "APP_ORDER_CREATED", "DELIVERY", order.getId().toString(),
            java.util.Map.of("client", clientName != null ? clientName : "N/A", "source", "Application", "action", "Nouvelle commande"));

        Delivery delivery = createDeliveryTask(order, "SYSTEM", "DELIVERY_CREATED", Map.of());
        eventPublisher.publishDeliveryCreated(order, delivery);
        erpLookupService.invalidateCache();

        return toOrderResponse(order, delivery);
    }

    // ── RabbitMQ / Odoo entry point ───────────────────────────────────────────

    @Transactional
    public void createFromCanonical(CanonicalDelivery canonical) {
        String erpOrderId = canonical.getMetadata() != null
                ? canonical.getMetadata().getExternalId() : null;

        if (StringUtils.hasText(erpOrderId)) {
            Optional<Order> existing = orderRepo.findByErpOrderId(erpOrderId);
            if (existing.isPresent()) {
            Order order = existing.get();
            applyCanonicalToOrder(order, canonical, false);
            orderRepo.save(order);
            log.info("Updated Odoo order id={} erpOrderId={}", order.getId(), erpOrderId);
            return;
            }
        }

        UUID companyId = TenantContext.get() != null ? UUID.fromString(TenantContext.get()) : null;
        Order order = Order.builder()
            .source(OrderSource.ODOO)
            .status(OrderStatus.PENDING)
            .companyId(companyId)
            .build();

        applyCanonicalToOrder(order, canonical, true);
        order = orderRepo.save(order);

        auditLogService.logAction(null, "ODOO_RECV_ORDER", "DELIVERY", order.getId().toString(),
            java.util.Map.of("erpId", erpOrderId != null ? erpOrderId : "N/A", "source", "Odoo", "action", "Import commande ERP"));

        Delivery delivery = createDeliveryTask(order, "SYSTEM", "DELIVERY_CREATED", Map.of());
        eventPublisher.publishDeliveryCreated(order, delivery);
        erpLookupService.invalidateCache();

        log.info("Created Odoo order id={} erpOrderId={}", order.getId(), erpOrderId);
    }

    // ── Query endpoints ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByClient(String clientId) {
        return orderRepo.findByClientIdOrderByCreatedAtDesc(clientId).stream()
                .map(this::toOrderResponseWithDelivery)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getActiveOrdersByClient(String clientId) {
        return orderRepo.findByClientIdAndStatusNotInOrderByCreatedAtDesc(clientId, TERMINAL).stream()
                .map(this::toOrderResponseWithDelivery)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId, String clientId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if (OrderSource.APP.equals(order.getSource()) && !clientId.equals(order.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        return toOrderResponseWithDelivery(order);
    }

    // ── Cancel order ──────────────────────────────────────────────────────────

    @Transactional
    public void cancelOrder(UUID orderId, String clientId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if (OrderSource.APP.equals(order.getSource()) && !clientId.equals(order.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        Delivery delivery = deliveryRepo.findByOrderId(order.getId())
                .orElse(null);

        if (delivery != null) {
            DeliveryStatus ds = delivery.getStatus();
            if (ds == DeliveryStatus.PICKED_UP || ds == DeliveryStatus.IN_TRANSIT) {
                throw AppException.forbidden("Cannot cancel order that is being delivered");
            }
            if (ds == DeliveryStatus.DELIVERED
                    || ds == DeliveryStatus.PARTIALLY_DELIVERED
                    || ds == DeliveryStatus.CANCELLED
                    || ds == DeliveryStatus.FAILED) {
                throw AppException.conflict("Order is already in terminal state");
            }

            // Release driver if assigned
            if (ds == DeliveryStatus.SCHEDULED && delivery.getDriverId() != null) {
                // driver will be released via event / workflow — for now just cancel
            }

            delivery.setStatus(DeliveryStatus.CANCELLED);
            delivery.setCancelledAt(LocalDateTime.now());
            delivery.setCancelledBy(Role.CLIENT);
            delivery.setCancelReason("Cancelled by client");
            deliveryRepo.save(delivery);

            appendHistory(delivery, DeliveryStatus.CANCELLED, clientId, Role.CLIENT, "DELIVERY_CANCELLED_BY_CLIENT", Map.of("clientId", clientId));
            eventPublisher.publishDeliveryCancelled(order, delivery, null);
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepo.save(order);

        outboxProcessor.enqueue("ERP_SYNC_CANCELLATION", Map.of("orderId", order.getId().toString()));
    }

    @Transactional
    public void adminCancelOrder(UUID orderId, UserPrincipal principal, String reason) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        String adminId = principal != null ? principal.getUserId() : "SYSTEM";

        Delivery delivery = deliveryRepo.findByOrderId(order.getId()).orElse(null);

        if (delivery != null) {
            DeliveryStatus ds = delivery.getStatus();
            if (ds == DeliveryStatus.PICKED_UP || ds == DeliveryStatus.IN_TRANSIT) {
                throw AppException.forbidden("Cannot cancel order that is being delivered");
            }
            if (ds == DeliveryStatus.DELIVERED
                    || ds == DeliveryStatus.PARTIALLY_DELIVERED
                    || ds == DeliveryStatus.CANCELLED
                    || ds == DeliveryStatus.FAILED) {
                throw AppException.conflict("Order is already in terminal state");
            }

            // If delivery is SCHEDULED (on a route), soft-remove the route stop
            if (ds == DeliveryStatus.SCHEDULED) {
                routeStopRepository.findActiveByDeliveryId(delivery.getId()).ifPresent(stop -> {
                    com.asm.delivery.entity.Route route = stop.getRoute();
                    if (route != null
                            && (route.getStatus() == com.asm.delivery.entity.RouteStatus.VALIDATED
                                || route.getStatus() == com.asm.delivery.entity.RouteStatus.IN_PROGRESS)) {
                        stop.setStatus(com.asm.delivery.entity.RouteStopStatus.REMOVED_CANCELLED);
                        stop.setRemovedAt(LocalDateTime.now());
                        stop.setRemovedReason("ORDER_CANCELLED");
                        stop.setRemovedBy(adminId);
                        routeStopRepository.save(stop);
                        routeWebSocketService.notifyDriverStopRemoved(
                            route.getDriverId(), route.getId(), route.getName(),
                            order.getClientName(), order.getErpOrderId(), "ORDER_CANCELLED");
                    } else if (route != null && route.getStatus() == com.asm.delivery.entity.RouteStatus.DRAFT) {
                        routeStopRepository.delete(stop);
                    }
                });
            }

            String cancelReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Cancelled by admin";
            delivery.setStatus(DeliveryStatus.CANCELLED);
            delivery.setCancelledAt(LocalDateTime.now());
            delivery.setCancelledBy(Role.ADMIN);
            delivery.setCancelReason(cancelReason);
            deliveryRepo.save(delivery);

            appendHistory(delivery, DeliveryStatus.CANCELLED, adminId, Role.ADMIN, "DELIVERY_CANCELLED_BY_ADMIN", Map.of("reason", cancelReason));
            eventPublisher.publishDeliveryCancelled(order, delivery, null);
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepo.save(order);

        auditLogService.logAction(principal, "ADMIN_CANCEL_ORDER", "ORDER", orderId.toString(),
                java.util.Map.of("reason", reason != null ? reason : ""));

        outboxProcessor.enqueue("ERP_SYNC_CANCELLATION", Map.of("orderId", order.getId().toString()));
    }

    @Transactional(readOnly = true)
    public CancellableResponse isCancellable(UUID orderId, String clientId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if (OrderSource.APP.equals(order.getSource()) && !clientId.equals(order.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        Delivery delivery = deliveryRepo.findByOrderId(order.getId()).orElse(null);
        if (delivery == null) {
            return new CancellableResponse(order.getStatus() == OrderStatus.PENDING, null);
        }

        return switch (delivery.getStatus()) {
            case UNSCHEDULED, SCHEDULED -> new CancellableResponse(true, null);
            case PICKED_UP, IN_TRANSIT    -> new CancellableResponse(false, "Delivery is already in progress");
            default -> new CancellableResponse(false, "Order is in terminal state");
        };
    }

    // ── Reorder ───────────────────────────────────────────────────────────────

    @Transactional
    public OrderResponse reorder(UUID orderId, String clientId) {
        Order original = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if (OrderSource.APP.equals(original.getSource()) && !clientId.equals(original.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        Order reorder = Order.builder()
            .source(OrderSource.APP)
                .clientId(original.getClientId())
                .clientName(original.getClientName())
                .clientPhone(original.getClientPhone())
                .clientEmail(original.getClientEmail())
                .originName(original.getOriginName())
                .originAddress(original.getOriginAddress())
                .originCity(original.getOriginCity())
                .originPostalCode(original.getOriginPostalCode())
                .originCountryCode(original.getOriginCountryCode())
                .dropoffAddress(original.getDropoffAddress())
                .dropoffCity(original.getDropoffCity())
                .dropoffPostalCode(original.getDropoffPostalCode())
                .dropoffCountryCode(original.getDropoffCountryCode())
                .dropoffLat(original.getDropoffLat())
                .dropoffLng(original.getDropoffLng())
                .deliveryInstructions(original.getDeliveryInstructions())
                .totalAmount(original.getTotalAmount())
                .currency(original.getCurrency())
                .priority(original.getPriority())
                .items(original.getItems())
                .totalQuantity(original.getTotalQuantity())
                .totalWeightKg(original.getTotalWeightKg())
                .status(OrderStatus.PENDING)
                .companyId(original.getCompanyId())
                .build();

        reorder = orderRepo.save(reorder);
        Delivery delivery = createDeliveryTask(reorder, clientId, "DELIVERY_CREATED", Map.of("clientId", clientId, "reordered", true));
        eventPublisher.publishDeliveryCreated(reorder, delivery);

        return toOrderResponse(reorder, delivery);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Delivery createDeliveryTask(Order order, String changedBy, String eventKey, Map<String, Object> params) {
        Delivery delivery = Delivery.builder()
                .order(order)
                .status(DeliveryStatus.UNSCHEDULED)
                .companyId(order.getCompanyId())
                .build();
        delivery = deliveryRepo.save(delivery);

        appendHistory(delivery, DeliveryStatus.UNSCHEDULED, changedBy, Role.SYSTEM, eventKey, params);
        return delivery;
    }

    private void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role, String eventKey, Map<String, Object> params) {
        String jsonParams = "{}";
        try {
            jsonParams = objectMapper.writeValueAsString(params != null ? params : Map.of());
        } catch (Exception ignored) {}
        
        historyRepo.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .eventKey(eventKey)
                .eventParams(jsonParams)
                .build());
    }

    private OrderResponse toOrderResponseWithDelivery(Order order) {
        Delivery delivery = deliveryRepo.findByOrderId(order.getId()).orElse(null);
        return toOrderResponse(order, delivery);
    }

    public OrderResponse toOrderResponse(Order order, Delivery delivery) {
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
                .erpExternalRef(order.getErpExternalRef())
                .odooSyncStatus(order.getOdooSyncStatus())
                .odooBackorderId(order.getOdooBackorderId())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    private LocalDateTime parseDateTime(String iso) {
        if (!StringUtils.hasText(iso)) return null;
        try {
            return LocalDateTime.parse(iso.replace("Z", "").replace("z", ""));
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(iso).toLocalDateTime();
            } catch (Exception ex) {
                return null;
            }
        }
    }

            private void applyCanonicalToOrder(Order order, CanonicalDelivery canonical, boolean isNew) {
            CanonicalDelivery.Origin origin = canonical.getOrigin();
            CanonicalDelivery.Destination dest = canonical.getDestination();
            CanonicalDelivery.Financial fin = canonical.getFinancial();
            CanonicalDelivery.Load load = canonical.getLoad();
            CanonicalDelivery.Planning plan = canonical.getPlanning();
            CanonicalDelivery.Metadata meta = canonical.getMetadata();
            CanonicalDelivery.Identity identity = canonical.getIdentity();

            List<OrderItem> items = load != null && load.getItems() != null
                ? load.getItems().stream().map(ci -> OrderItem.builder()
                .id(ci.getId())
                .sku(ci.getSku())
                .name(ci.getName())
                .quantity(ci.getQuantity())
                .quantityDone(ci.getQuantityDone())
                .unitWeightKg(ci.getUnitWeightKg())
                .unitPrice(null)
                .build())
                .collect(Collectors.toList())
                : List.of();

            CanonicalDelivery.Contact destContact = dest != null ? dest.getContact() : null;
            CanonicalDelivery.Address destAddress = dest != null ? dest.getAddress() : null;
            CanonicalDelivery.Address origAddress = origin != null ? origin.getAddress() : null;
            CanonicalDelivery.Contact origContact = origin != null ? origin.getContact() : null;

            order.setSource(OrderSource.ODOO);
            order.setSchemaVersion(identity != null && StringUtils.hasText(identity.getSchemaVersion())
                ? identity.getSchemaVersion() : "1.0.0");
            order.setClientId(null);
            order.setClientName(destContact != null && StringUtils.hasText(destContact.getName()) ? destContact.getName() : "N/A");
            order.setClientPhone(destContact != null ? destContact.getPhone() : null);
            order.setClientEmail(destContact != null ? destContact.getEmail() : null);
            order.setErpOrderId(meta != null ? meta.getExternalId() : null);
            order.setErpExternalRef(identity != null ? identity.getExternalReference() : null);

            order.setOriginName(origin != null ? origin.getName() : null);
            order.setOriginAddress(origAddress != null ? origAddress.getFullAddress() : null);
            order.setOriginCity(origAddress != null ? origAddress.getCity() : null);
            order.setOriginPostalCode(origAddress != null ? origAddress.getPostalCode() : null);
            order.setOriginCountryCode(origAddress != null ? origAddress.getCountryCode() : null);
            order.setOriginContactName(origContact != null ? origContact.getName() : null);
            order.setOriginContactPhone(origContact != null ? origContact.getPhone() : null);
            order.setOriginContactEmail(origContact != null ? origContact.getEmail() : null);

            order.setDropoffAddress(destAddress != null && StringUtils.hasText(destAddress.getFullAddress())
                ? destAddress.getFullAddress() : "N/A");
            order.setDropoffCity(destAddress != null ? destAddress.getCity() : null);
            order.setDropoffPostalCode(destAddress != null ? destAddress.getPostalCode() : null);
            order.setDropoffCountryCode(destAddress != null && StringUtils.hasText(destAddress.getCountryCode())
                ? destAddress.getCountryCode() : "TN");
            order.setDropoffLat(null);
            order.setDropoffLng(null);
            order.setDeliveryInstructions(dest != null ? dest.getDeliveryInstructions() : null);

            order.setTotalAmount(fin != null && fin.getTotalAmount() != null ? fin.getTotalAmount() : BigDecimal.ZERO);
            order.setCurrency(fin != null && StringUtils.hasText(fin.getCurrency()) ? fin.getCurrency() : "TND");

            order.setScheduledAt(plan != null ? parseDateTime(plan.getScheduledAt()) : null);
            order.setPriority(parsePriority(plan != null ? plan.getPriority() : null));

            order.setItems(items);
            order.setTotalQuantity(load != null && load.getTotalQuantity() != null ? load.getTotalQuantity() : 0);
            order.setTotalWeightKg(load != null && load.getTotalWeightKg() != null ? load.getTotalWeightKg() : BigDecimal.ZERO);
            order.setLastSyncedAt(meta != null ? parseDateTime(meta.getLastSyncedAt()) : null);

            if (isNew) {
                order.setStatus(OrderStatus.PENDING);
            }
            }

    private OrderPriority parsePriority(String value) {
        if (!StringUtils.hasText(value)) return OrderPriority.NORMAL;
        try {
            return OrderPriority.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return OrderPriority.NORMAL;
        }
    }
}
