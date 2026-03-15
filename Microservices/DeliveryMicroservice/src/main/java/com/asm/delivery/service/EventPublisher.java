package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.outgoing-exchange}")
    private String outgoingExchange;

    // ── delivery.created ─────────────────────────────────────────────────────

    public void publishDeliveryCreated(Order order, Delivery delivery) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId",     delivery.getId());
        payload.put("orderId",        order.getId());
        payload.put("clientId",       order.getClientId());
        payload.put("clientName",     order.getClientName());
        payload.put("clientPhone",    order.getClientPhone());
        payload.put("source",         order.getSource());
        payload.put("erpOrderId",     order.getErpOrderId());
        payload.put("items",          order.getItems());
        payload.put("totalAmount",    order.getTotalAmount());
        payload.put("paymentType",    order.getPaymentType());
        payload.put("amountToCollect",order.getAmountToCollect());
        payload.put("dropoffAddress", order.getDropoffAddress());
        payload.put("dropoffLat",     order.getDropoffLat());
        payload.put("dropoffLng",     order.getDropoffLng());
        payload.put("scheduledAt",    order.getScheduledAt());
        payload.put("priority",       order.getPriority());
        payload.put("createdAt",      LocalDateTime.now());

        publish("delivery.created", payload);
    }

    // ── delivery.assigned ────────────────────────────────────────────────────

    public void publishDeliveryAssigned(Order order, Delivery delivery, UUID driverId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId",  delivery.getId());
        payload.put("orderId",     order.getId());
        payload.put("clientId",    order.getClientId());
        payload.put("driverId",    driverId);
        payload.put("assignedAt",  delivery.getAssignedAt());

        publish("delivery.assigned", payload);
    }

    // ── delivery.picked_up ───────────────────────────────────────────────────

    public void publishDeliveryPickedUp(Order order, Delivery delivery) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId",  delivery.getId());
        payload.put("orderId",     order.getId());
        payload.put("clientId",    order.getClientId());
        payload.put("driverId",    delivery.getDriverId());
        payload.put("pickedUpAt",  delivery.getPickedUpAt());

        publish("delivery.picked_up", payload);
    }

    // ── delivery.in_transit ──────────────────────────────────────────────────

    public void publishDeliveryInTransit(Order order, Delivery delivery, BigDecimal lat, BigDecimal lng) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId",   delivery.getId());
        payload.put("orderId",      order.getId());
        payload.put("clientId",     order.getClientId());
        payload.put("driverId",     delivery.getDriverId());
        payload.put("driverLat",    lat);
        payload.put("driverLng",    lng);
        payload.put("inTransitAt",  delivery.getInTransitAt());

        publish("delivery.in_transit", payload);
    }

    // ── delivery.completed ───────────────────────────────────────────────────

    public void publishDeliveryCompleted(Order order, Delivery delivery, UUID driverId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId",     delivery.getId());
        payload.put("orderId",        order.getId());
        payload.put("clientId",       order.getClientId());
        payload.put("clientName",     order.getClientName());
        payload.put("clientPhone",    order.getClientPhone());
        payload.put("driverId",       driverId);
        // n8n fields
        payload.put("source",         order.getSource());
        payload.put("erpOrderId",     order.getErpOrderId());
        payload.put("items",          order.getItems());
        payload.put("totalAmount",    order.getTotalAmount());
        payload.put("amountCollected",order.getAmountToCollect());
        payload.put("completedAt",    delivery.getCompletedAt());

        publish("delivery.completed", payload);
    }

    // ── delivery.cancelled ───────────────────────────────────────────────────

    public void publishDeliveryCancelled(Order order, Delivery delivery, UUID driverId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId",   delivery.getId());
        payload.put("orderId",      order.getId());
        payload.put("clientId",     order.getClientId());
        payload.put("driverId",     driverId);
        payload.put("cancelledBy",  delivery.getCancelledBy());
        payload.put("reason",       delivery.getCancelReason());
        payload.put("cancelledAt",  delivery.getCancelledAt());

        publish("delivery.cancelled", payload);
    }

    // ── delivery.failed ──────────────────────────────────────────────────────

    public void publishDeliveryFailed(Order order, Delivery delivery, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId",  delivery.getId());
        payload.put("orderId",     order.getId());
        payload.put("clientId",    order.getClientId());
        payload.put("driverId",    delivery.getDriverId());
        payload.put("reason",      reason);
        payload.put("failedAt",    delivery.getFailedAt());

        publish("delivery.failed", payload);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void publish(String routingKey, Object payload) {
        try {
            Map<String, Object> envelope = Map.of(
                    "event",     routingKey,
                    "timestamp", LocalDateTime.now().toString(),
                    "data",      payload
            );
            rabbitTemplate.convertAndSend(outgoingExchange, routingKey, envelope);
            log.info("Published → [{}]", routingKey);
        } catch (Exception e) {
            log.error("Failed to publish event [{}]: {}", routingKey, e.getMessage());
        }
    }
}
