package com.asm.delivery.service;

import com.asm.delivery.dto.canonical.CanonicalDelivery;
import com.asm.delivery.dto.request.CreateOrderRequest;
import com.asm.delivery.dto.response.CancellableResponse;
import com.asm.delivery.dto.response.OrderResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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

    private static final List<String> TERMINAL = List.of("CANCELLED", "DELIVERED", "FAILED");

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

        Order order = Order.builder()
                .source("APP")
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
                .paymentType(req.getPaymentType())
                .amountToCollect(req.getAmountToCollect())
                .scheduledAt(parseDateTime(req.getScheduledAt()))
                .priority(StringUtils.hasText(req.getPriority()) ? req.getPriority() : "NORMAL")
                .items(items)
                .totalQuantity(totalQty)
                .totalWeightKg(totalWeight)
                .status("PENDING")
                .build();

        order = orderRepo.save(order);

        Delivery delivery = createDeliveryTask(order, "SYSTEM", "Order created from app");
        eventPublisher.publishDeliveryCreated(order, delivery);

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

        Order order = Order.builder()
            .source("ODOO")
            .status("PENDING")
            .build();

        applyCanonicalToOrder(order, canonical, true);
        order = orderRepo.save(order);

        Delivery delivery = createDeliveryTask(order, "SYSTEM", "Order received from Odoo");
        eventPublisher.publishDeliveryCreated(order, delivery);

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

        if ("APP".equals(order.getSource()) && !clientId.equals(order.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        return toOrderResponseWithDelivery(order);
    }

    // ── Cancel order ──────────────────────────────────────────────────────────

    @Transactional
    public void cancelOrder(UUID orderId, String clientId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if ("APP".equals(order.getSource()) && !clientId.equals(order.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        Delivery delivery = deliveryRepo.findByOrderId(order.getId())
                .orElse(null);

        if (delivery != null) {
            String ds = delivery.getStatus();
            if ("PICKED_UP".equals(ds) || "IN_TRANSIT".equals(ds) || "DELIVERED".equals(ds)) {
                throw AppException.forbidden("Cannot cancel order that is being delivered");
            }
            if ("CANCELLED".equals(ds) || "FAILED".equals(ds)) {
                throw AppException.conflict("Order is already in terminal state");
            }

            // Release driver if assigned
            if ("ASSIGNED".equals(ds) && delivery.getDriverId() != null) {
                // driver will be released via event / workflow — for now just cancel
            }

            delivery.setStatus("CANCELLED");
            delivery.setCancelledAt(LocalDateTime.now());
            delivery.setCancelledBy("CLIENT");
            delivery.setCancelReason("Cancelled by client");
            deliveryRepo.save(delivery);

            appendHistory(delivery, "CANCELLED", clientId, "CLIENT", "Cancelled by client");
            eventPublisher.publishDeliveryCancelled(order, delivery, null);
        }

        order.setStatus("CANCELLED");
        orderRepo.save(order);
    }

    @Transactional(readOnly = true)
    public CancellableResponse isCancellable(UUID orderId, String clientId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if ("APP".equals(order.getSource()) && !clientId.equals(order.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        Delivery delivery = deliveryRepo.findByOrderId(order.getId()).orElse(null);
        if (delivery == null) {
            return new CancellableResponse("PENDING".equals(order.getStatus()), null);
        }

        return switch (delivery.getStatus()) {
            case "WAITING_DRIVER", "ASSIGNED" -> new CancellableResponse(true, null);
            case "PICKED_UP", "IN_TRANSIT"    -> new CancellableResponse(false, "Delivery is already in progress");
            default -> new CancellableResponse(false, "Order is in terminal state");
        };
    }

    // ── Reorder ───────────────────────────────────────────────────────────────

    @Transactional
    public OrderResponse reorder(UUID orderId, String clientId) {
        Order original = orderRepo.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));

        if ("APP".equals(original.getSource()) && !clientId.equals(original.getClientId())) {
            throw AppException.forbidden("Not your order");
        }

        Order reorder = Order.builder()
                .source("APP")
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
                .paymentType(original.getPaymentType())
                .amountToCollect(original.getAmountToCollect())
                .priority(original.getPriority())
                .items(original.getItems())
                .totalQuantity(original.getTotalQuantity())
                .totalWeightKg(original.getTotalWeightKg())
                .status("PENDING")
                .build();

        reorder = orderRepo.save(reorder);
        Delivery delivery = createDeliveryTask(reorder, clientId, "Reorder");
        eventPublisher.publishDeliveryCreated(reorder, delivery);

        return toOrderResponse(reorder, delivery);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Delivery createDeliveryTask(Order order, String changedBy, String note) {
        Delivery delivery = Delivery.builder()
                .order(order)
                .status("WAITING_DRIVER")
                .build();
        delivery = deliveryRepo.save(delivery);

        appendHistory(delivery, "WAITING_DRIVER", changedBy, "SYSTEM", note);
        return delivery;
    }

    private void appendHistory(Delivery delivery, String status, String changedBy, String role, String note) {
        historyRepo.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .note(note)
                .build());
    }

    private OrderResponse toOrderResponseWithDelivery(Order order) {
        Delivery delivery = deliveryRepo.findByOrderId(order.getId()).orElse(null);
        return toOrderResponse(order, delivery);
    }

    public OrderResponse toOrderResponse(Order order, Delivery delivery) {
        return OrderResponse.builder()
                .id(order.getId())
                .source(order.getSource())
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
                .paymentType(order.getPaymentType())
                .amountToCollect(order.getAmountToCollect())
                .priority(order.getPriority())
                .scheduledAt(order.getScheduledAt())
                .items(order.getItems())
                .totalQuantity(order.getTotalQuantity())
                .totalWeightKg(order.getTotalWeightKg())
                .status(order.getStatus())
                .deliveryId(delivery != null ? delivery.getId() : null)
                .deliveryStatus(delivery != null ? delivery.getStatus() : null)
                .erpOrderId(order.getErpOrderId())
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

            order.setSource("ODOO");
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
            order.setPaymentType(fin != null && StringUtils.hasText(fin.getPaymentType()) ? fin.getPaymentType().toUpperCase() : "COD");
            order.setAmountToCollect(fin != null && fin.getAmountToCollect() != null ? fin.getAmountToCollect() : BigDecimal.ZERO);

            order.setScheduledAt(plan != null ? parseDateTime(plan.getScheduledAt()) : null);
            order.setPriority(plan != null && StringUtils.hasText(plan.getPriority()) ? plan.getPriority().toUpperCase() : "NORMAL");

            order.setItems(items);
            order.setTotalQuantity(load != null && load.getTotalQuantity() != null ? load.getTotalQuantity() : 0);
            order.setTotalWeightKg(load != null && load.getTotalWeightKg() != null ? load.getTotalWeightKg() : BigDecimal.ZERO);
            order.setLastSyncedAt(meta != null ? parseDateTime(meta.getLastSyncedAt()) : null);

            if (isNew) {
                order.setStatus("PENDING");
            }
            }
}
