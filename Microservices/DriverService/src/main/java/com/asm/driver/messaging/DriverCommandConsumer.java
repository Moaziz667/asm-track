package com.asm.driver.messaging;

import com.asm.driver.config.RabbitMQConfig;
import com.asm.driver.service.InternalDriverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Applies Delivery -> Driver commands delivered over {@code driver.commands}. Currently the live
 * driver location (high-frequency, fire-and-forget) — previously a synchronous HTTP PUT from
 * DeliveryService. A malformed message is dropped (not retryable); other failures dead-letter to
 * {@code driver.location.update.dlq}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DriverCommandConsumer {

    private final InternalDriverService internalDriverService;

    @RabbitListener(queues = RabbitMQConfig.DRIVER_LOCATION_QUEUE)
    public void onLocationUpdate(Map<String, Object> event) {
        Object driverId = event.get("driverId");
        Object lat = event.get("lat");
        Object lng = event.get("lng");
        if (driverId == null || lat == null || lng == null) {
            log.warn("DriverCommandConsumer: incomplete location event, dropping: {}", event);
            return; // ack + drop — not retryable
        }
        internalDriverService.updateLocation(
                UUID.fromString(driverId.toString()),
                new BigDecimal(lat.toString()),
                new BigDecimal(lng.toString()));
    }
}
