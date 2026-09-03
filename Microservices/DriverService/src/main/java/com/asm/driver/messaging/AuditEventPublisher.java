package com.asm.driver.messaging;

import com.asm.driver.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes this service's audit events to the shared {@code audit.exchange}, where DeliveryService
 * persists them alongside everyone else's. Same shape as AppBackend's publisher, on purpose: the
 * consumer reads one payload, not one per producer.
 *
 * <p>The company travels in the AMQP header, added by {@code TenantMessagePostProcessor} from the
 * current {@code TenantContext}, so nothing here has to carry it by hand.
 *
 * <p>Best-effort by design. A broker hiccup must never fail the operation the driver or the
 * dispatcher just performed — the local row is written first and the startup backfill replays
 * whatever never made it out.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /** @return true when the broker accepted the event, false when it must be replayed later. */
    public boolean publish(String action, String actorName, String actorRole,
                           String resourceId, String details) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("action", action);
            event.put("actorName", actorName != null ? actorName : "SYSTEM");
            event.put("actorRole", actorRole != null ? actorRole : "SYSTEM");
            event.put("resourceId", resourceId != null ? resourceId : "");
            event.put("details", details != null ? details : "{}");
            // Every event this service emits concerns a driver, which is what the console filters on.
            event.put("targetEntity", "DRIVER");
            event.put("timestamp", Instant.now().toString());
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AUDIT_EXCHANGE, RabbitMQConfig.AUDIT_ROUTING_KEY, event);
            return true;
        } catch (Exception e) {
            log.warn("Audit event not published, will be replayed at next startup — action={}: {}",
                    action, e.getMessage());
            return false;
        }
    }
}
