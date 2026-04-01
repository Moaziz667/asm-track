package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

// TODO Phase 2: replace with n8n webhook calls
@Service
@Slf4j
public class EventPublisher {

    public void publishDeliveryCreated(Order order, Delivery delivery) {
        log.info("EVENT delivery.created orderId={} deliveryId={}",
                order != null ? order.getId() : null, delivery.getId());
    }

    public void publishDeliveryAssigned(Order order, Delivery delivery, UUID driverId) {
        log.info("EVENT delivery.assigned orderId={} deliveryId={} driverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), driverId);
    }

    public void publishDeliveryPickedUp(Order order, Delivery delivery) {
        log.info("EVENT delivery.picked_up orderId={} deliveryId={}",
                order != null ? order.getId() : null, delivery.getId());
    }

    public void publishDeliveryInTransit(Order order, Delivery delivery,
            BigDecimal lat, BigDecimal lng) {
        log.info("EVENT delivery.in_transit orderId={} deliveryId={} lat={} lng={}",
                order != null ? order.getId() : null,
                delivery.getId(), lat, lng);
    }

    public void publishDeliveryCompleted(Order order, Delivery delivery, UUID driverId) {
        log.info("EVENT delivery.completed orderId={} deliveryId={} driverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), driverId);
    }

    public void publishDeliveryFailed(Order order, Delivery delivery, String reason) {
        log.info("EVENT delivery.failed orderId={} deliveryId={} reason={}",
                order != null ? order.getId() : null,
                delivery.getId(), reason);
    }

    public void publishDeliveryCancelled(Order order, Delivery delivery, UUID driverId) {
        log.info("EVENT delivery.cancelled orderId={} deliveryId={} driverId={}",
                order != null ? order.getId() : null,
                delivery.getId(), driverId);
    }
}
