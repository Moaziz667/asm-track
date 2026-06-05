package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.delivery.entity.AuditLog;
import com.asm.delivery.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Persists audit events published by other services (currently AppBackend admin-user actions) to
 * the {@code audit.exchange}. The actor (name/role) travels in the payload — captured from the
 * originating SecurityContext — so the stored row names the real dispatcher, not "SERVICE".
 *
 * <p>A malformed payload is acknowledged-and-dropped (it can never succeed on retry); any other
 * failure propagates so the container's retry policy applies, then dead-letters to {@code audit.log.dlq}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventConsumer {

    private final AuditLogService auditLogService;

    @RabbitListener(queues = RabbitMQConfig.AUDIT_QUEUE)
    public void onAuditEvent(Map<String, Object> event) {
        String action = str(event.get("action"));
        if (action == null) {
            log.warn("AuditEventConsumer: event missing 'action', dropping: {}", event);
            return; // ack + drop — not retryable
        }
        AuditLog log = AuditLog.builder()
                .actorName(str(event.getOrDefault("actorName", "SERVICE")))
                .actorRole(str(event.getOrDefault("actorRole", "SERVICE")))
                .action(action)
                .targetEntity(str(event.getOrDefault("targetEntity", "ADMIN_USER")))
                .resourceId(str(event.getOrDefault("resourceId", "")))
                .details(str(event.getOrDefault("details", "{}")))
                .ipAddress("internal")
                .build();
        auditLogService.logRaw(log);
    }

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }
}
