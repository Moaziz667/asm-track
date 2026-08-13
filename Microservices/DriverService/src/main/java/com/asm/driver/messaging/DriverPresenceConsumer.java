package com.asm.driver.messaging;

import com.asm.driver.config.RabbitMQConfig;
import com.asm.driver.service.InternalDriverService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Records what DeliveryService sees of a driver's realtime connection.
 *
 * <p>The tenant arrives as an AMQP header and is applied before this runs, so nothing here has to
 * carry it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DriverPresenceConsumer {

    private final InternalDriverService internalDriverService;

    @RabbitListener(queues = RabbitMQConfig.DRIVER_PRESENCE_QUEUE)
    public void onPresence(Map<String, Object> event) {
        Object driverId = event.get("driverId");
        Object connected = event.get("connected");
        if (driverId == null || connected == null) {
            log.warn("DriverPresenceConsumer: incomplete event, dropping: {}", event);
            return;             // ack + drop — nothing to retry
        }
        internalDriverService.applyPresence(
                UUID.fromString(driverId.toString()),
                Boolean.parseBoolean(connected.toString()),
                seenAt(event.get("seenAt")));
    }

    /** Dated at the source, so a backed-up queue cannot make a driver look fresher than he is. */
    private static LocalDateTime seenAt(Object raw) {
        try {
            return raw == null ? LocalDateTime.now()
                    : LocalDateTime.ofInstant(Instant.parse(raw.toString()), ZoneId.systemDefault());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
