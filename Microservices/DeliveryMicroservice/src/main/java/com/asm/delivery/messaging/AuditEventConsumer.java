package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.delivery.entity.AuditLog;
import com.asm.delivery.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final SimpMessagingTemplate ws;

    @RabbitListener(queues = RabbitMQConfig.AUDIT_QUEUE)
    public void onAuditEvent(Map<String, Object> event) {
        String action = str(event.get("action"));
        if (action == null) {
            log.warn("AuditEventConsumer: event missing 'action', dropping: {}", event);
            return; // ack + drop — not retryable
        }

        // S2: a force-logout signal is relayed straight to the user's client, not persisted as audit
        // (the originating FORCE_LOGOUT_* row already records the action). Admin users match by email
        // on /topic/admin.security; drivers are targeted directly on their own /topic/driver.<id>.
        if ("SESSION_REVOKED".equals(action)) {
            String driverId = str(event.get("driverId"));
            String sub = str(event.get("sub"));
            if (driverId != null) {
                ws.convertAndSend("/topic/driver." + driverId,
                        Map.of("type", "session.revoked", "driverId", driverId));
            } else if (sub != null) {
                // Admin clients match this against their own token's `sub` (immutable Keycloak subject).
                ws.convertAndSend("/topic/admin.security",
                        Map.of("type", "session.revoked", "sub", sub));
            }
            return;
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
