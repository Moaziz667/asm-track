package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.tenant.TenantContext;
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
        // Set tenant context from message header if present
        String companyId = (String) event.get("X-Company-Id");
        if (companyId != null) {
            try {
                TenantContext.set(UUID.fromString(companyId));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid X-Company-Id in driver status event: {}", companyId);
            }
        }

        try {
            String driverId   = (String) event.get("driverId");
            String status     = (String) event.get("status");
            String driverName = (String) event.get("driverName");

            if (driverId == null || status == null) {
                log.warn("DriverStatusConsumer: incomplete event received, skipping: {}", event);
                return;
            }

            webSocketService.notifyDriverStatusChanged(
                    UUID.fromString(driverId),
                    status,
                    driverName != null ? driverName : "");

        } catch (Exception e) {
            log.error("DriverStatusConsumer: failed to process event {}: {}", event, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
