package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes Delivery → Driver commands to {@code driver.commands}. Live driver location is
 * high-frequency and best-effort, so it is fire-and-forget over the broker instead of a
 * synchronous HTTP PUT to DriverService (which previously coupled Delivery to Driver uptime).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DriverCommandPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishLocationUpdate(UUID driverId, BigDecimal lat, BigDecimal lng) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("driverId", driverId.toString());
            event.put("lat", lat);
            event.put("lng", lng);
            event.put("timestamp", Instant.now().toString());
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.DRIVER_COMMANDS_EXCHANGE, RabbitMQConfig.DRIVER_LOCATION_ROUTING, event);
        } catch (Exception e) {
            log.warn("Failed to publish driver location for driverId={}: {}", driverId, e.getMessage());
        }
    }
}
