package com.asm.delivery.service;


import com.asm.delivery.dto.canonical.CanonicalDelivery;
import com.asm.delivery.dto.response.OrderResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.erp.ErpLookupService;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.erp.ErpSyncService;
import com.asm.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final OrderGeocodingService orderGeocodingService;
    private final com.asm.delivery.repository.RouteStopRepository routeStopRepository;
    private final com.asm.delivery.repository.RouteRepository routeRepository;
    private final com.asm.delivery.service.route.RoutePlanningService routePlanningService;
    private final com.asm.delivery.service.route.RouteWebSocketService routeWebSocketService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Trigger async geocoding once the create transaction has committed (so the row is visible). */
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

    // ── ERP import entry point (RabbitMQ / Odoo) ──────────────────────────────

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


        Order order = Order.builder()
            .source(OrderSource.ODOO)
            .status(OrderStatus.PENDING)

            .build();

        applyCanonicalToOrder(order, canonical, true);
        order = orderRepo.save(order);

        auditLogService.logAction(null, "ODOO_RECV_ORDER", "DELIVERY", order.getId().toString(),
            java.util.Map.of("erpId", erpOrderId != null ? erpOrderId : "N/A", "source", "Odoo", "action", "Import commande ERP"));

        // Stamp the real actor when a human triggered the import (ActorContext reads the request's
        // X-User-* headers); falls back to SYSTEM for the headless Odoo webhook/poller. Consistent
        // with ErpLookupService so the audit never reads a hardcoded "Système" for a manual import.
        Delivery delivery = createDeliveryTask(order, com.asm.delivery.web.ActorContext.changedBy(), "DELIVERY_CREATED", Map.of());
        eventPublisher.publishDeliveryCreated(order, delivery);
        erpLookupService.invalidateCache();

        log.info("Created Odoo order id={} erpOrderId={}", order.getId(), erpOrderId);
    }

    // ── Query endpoints ───────────────────────────────────────────────────────

    @Transactional
    public void adminCancelOrder(UUID orderId, UserPrincipal principal, String reason) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        String adminId = principal != null ? principal.getUserId() : "SYSTEM";

        Delivery delivery = deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(order.getId()).orElse(null);

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

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Delivery createDeliveryTask(Order order, String changedBy, String eventKey, Map<String, Object> params) {
        Delivery delivery = Delivery.builder()
                .order(order)
                .status(DeliveryStatus.UNSCHEDULED)

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
                .customFields(order.getCustomFields())
                .totalQuantity(order.getTotalQuantity())
                .totalWeightKg(order.getTotalWeightKg())
                .status(order.getStatus().name())
                .deliveryId(delivery != null ? delivery.getId() : null)
                .deliveryStatus(delivery != null ? delivery.getStatus().name() : null)
                .erpOrderId(order.getErpOrderId())
                .erpExternalRef(order.getErpExternalRef())
                .erpSyncStatus(order.getErpSyncStatus())
                .erpBackorderId(order.getErpBackorderId())
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
