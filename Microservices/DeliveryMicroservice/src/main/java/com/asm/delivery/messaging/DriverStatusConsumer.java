package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.delivery.service.route.RouteWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DriverStatusConsumer {

    private final RouteWebSocketService webSocketService;

    @RabbitListener(queues = RabbitMQConfig.DRIVER_STATUS_QUEUE)
    public void onDriverStatusChanged(Map<String, Object> event) {
        try {
            String driverId   = (String) event.get("driverId");
            String companyId  = (String) event.get("companyId");
            String status     = (String) event.get("status");
            String driverName = (String) event.get("driverName");

            if (driverId == null || companyId == null || status == null) {
                log.warn("DriverStatusConsumer: incomplete event received, skipping: {}", event);
                return;
            }

            webSocketService.notifyDriverStatusChanged(
                    UUID.fromString(companyId),
                    UUID.fromString(driverId),
                    status,
                    driverName != null ? driverName : "");

        } catch (Exception e) {
            log.error("DriverStatusConsumer: failed to process event {}: {}", event, e.getMessage());
        }
    }
}
