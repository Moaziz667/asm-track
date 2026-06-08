package com.asm.driver.service;

import com.asm.driver.config.RabbitMQConfig;
import com.asm.driver.entity.DriverOnlineStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * S2 of force-logout: relays a session-revoked signal through the shared {@code audit.exchange}
     * so DeliveryService (which owns the WebSocket broker) can push it to the driver's
     * {@code /topic/driver.<id>} topic and the Flutter app logs itself out instantly. The short access
     * token lifespan (S1) remains the backstop if the device is offline and misses this.
     */
    public void publishSessionRevoked(UUID driverId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("action", "SESSION_REVOKED");
            event.put("driverId", driverId.toString());
            event.put("timestamp", Instant.now().toString());
            // Shared audit channel — consumed by DeliveryService's AuditEventConsumer.
            rabbitTemplate.convertAndSend("audit.exchange", "audit.log", event);
        } catch (Exception e) {
            log.warn("Failed to publish driver session-revoked for driverId={}: {}", driverId, e.getMessage());
        }
    }

    public void publishStatusChanged(UUID driverId,
                                     DriverOnlineStatus oldStatus, DriverOnlineStatus newStatus,
                                     String driverName) {        try {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "driver.status_changed");
            event.put("driverId", driverId.toString());
                        event.put("status", newStatus.name());
            event.put("previousStatus", oldStatus != null ? oldStatus.name() : "OFFLINE");
            event.put("driverName", driverName);
            event.put("timestamp", Instant.now().toString());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.DRIVER_EVENTS_EXCHANGE,
                    "driver.status.changed",
                    event
            );
        } catch (Exception e) {
            log.warn("Failed to publish driver status event for driverId={}: {}", driverId, e.getMessage());
        }
    }
}
