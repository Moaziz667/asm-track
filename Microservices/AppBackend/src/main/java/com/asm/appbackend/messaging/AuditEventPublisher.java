package com.asm.appbackend.messaging;

import com.asm.appbackend.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes admin-user audit events to the {@code audit.exchange}, replacing the previous blocking
 * HTTP POST to DeliveryService. The real actor (captured from the SecurityContext at the controller)
 * travels in the payload so the persisted audit row names the dispatcher, not "SERVICE".
 * Best-effort: a broker hiccup is logged, never blocks the user-facing operation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishAdminUserAudit(String action, String actorName, String actorRole,
                                      String resourceId, String details) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("action", action);
            event.put("actorName", actorName);
            event.put("actorRole", actorRole != null ? actorRole : "ADMIN");
            event.put("resourceId", resourceId != null ? resourceId : "");
            event.put("details", details != null ? details : "{}");
            event.put("targetEntity", "ADMIN_USER");
            event.put("timestamp", Instant.now().toString());
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AUDIT_EXCHANGE, RabbitMQConfig.AUDIT_ROUTING_KEY, event);
        } catch (Exception e) {
            log.warn("Failed to publish audit event action={}: {}", action, e.getMessage());
        }
    }

    /**
     * Signals that a user's session was force-revoked, so their client can log itself out instantly
     * (S2) instead of waiting for the access token to expire (S1). Travels over the same audit
     * exchange; DeliveryService relays it to the {@code /topic/admin.security} WebSocket topic.
     * Best-effort — the short token lifespan is the backstop if this never arrives.
     */
    public void publishSessionRevoked(String sub) {
        if (sub == null || sub.isBlank()) return;
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("action", "SESSION_REVOKED");
            // Key on the immutable OIDC subject (Keycloak user id) — the client matches it against its
            // own token's `sub`. Stable across email/username changes, and what the logout_token carries.
            event.put("sub", sub);
            event.put("timestamp", Instant.now().toString());
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AUDIT_EXCHANGE, RabbitMQConfig.AUDIT_ROUTING_KEY, event);
        } catch (Exception e) {
            log.warn("Failed to publish session-revoked event for sub {}: {}", sub, e.getMessage());
        }
    }

    /**
     * Driver variant of {@link #publishSessionRevoked}: drivers are targeted on their own
     * {@code /topic/driver.<driverId>} topic (keyed by the business driver id, not sub), so the
     * back-channel-logout path resolves the logout token's sub → driver id and publishes that here.
     */
    public void publishDriverSessionRevoked(String driverId) {
        if (driverId == null || driverId.isBlank()) return;
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("action", "SESSION_REVOKED");
            event.put("driverId", driverId);
            event.put("timestamp", Instant.now().toString());
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AUDIT_EXCHANGE, RabbitMQConfig.AUDIT_ROUTING_KEY, event);
        } catch (Exception e) {
            log.warn("Failed to publish driver session-revoked event for {}: {}", driverId, e.getMessage());
        }
    }
}
