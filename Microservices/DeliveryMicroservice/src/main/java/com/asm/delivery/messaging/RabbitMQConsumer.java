package com.asm.delivery.messaging;

import com.asm.delivery.dto.canonical.CanonicalDelivery;
import com.asm.delivery.service.DriverDeliveryService;
import com.asm.delivery.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RabbitMQConsumer {

    private final OrderService          orderService;
    private final DriverDeliveryService driverDeliveryService;

    // ── Incoming: Canonical orders from Mapper Service ────────────────────────

    @RabbitListener(queues = "${app.rabbitmq.queue-orders-created}",
            autoStartup = "${app.rabbitmq.consume-canonical:true}")
    public void consumeCanonicalOrder(Object message) {
        try {
            CanonicalDelivery canonical = extractCanonical(message);
            if (canonical == null) {
                log.warn("Received null or unparseable canonical delivery (type={})", message != null ? message.getClass().getName() : "null");
                logRawMessage(message);
                return;
            }
            log.info("Received canonical order from Mapper: erpOrderId={}",
                    canonical.getMetadata() != null ? canonical.getMetadata().getExternalId() : "N/A");
            orderService.createFromCanonical(canonical);
        } catch (Exception e) {
            log.error("Failed to process canonical order: {}", e.getMessage(), e);
            // Do not rethrow — avoid poisoning the queue. Log and move on.
        }
    }

    // ── Incoming: Workflow Service commands ───────────────────────────────────

    @RabbitListener(queues = "${app.rabbitmq.queue-workflow-commands}")
    public void consumeWorkflowCommand(Map<String, Object> message) {
        try {
            String command    = (String) message.get("command");
            String deliveryId = (String) message.get("deliveryId");
            String reason     = (String) message.getOrDefault("reason", "Workflow command");

            if (command == null || deliveryId == null) {
                log.warn("Invalid workflow command: {}", message);
                return;
            }

            UUID id = UUID.fromString(deliveryId);

            switch (command) {
                case "workflow.reset_to_waiting" ->
                        driverDeliveryService.resetToWaiting(id);
                case "workflow.force_cancel" ->
                        driverDeliveryService.forceCancel(id, reason);
                case "workflow.force_fail" ->
                        driverDeliveryService.forceFail(id, reason);
                default ->
                        log.warn("Unknown workflow command: {}", command);
            }
        } catch (Exception e) {
            log.error("Failed to process workflow command: {}", e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private CanonicalDelivery extractCanonical(Object message) {
        if (message instanceof CanonicalDelivery cd) return cd;

        if (message instanceof byte[] bytes) {
            return parseJsonCanonical(new String(bytes));
        }

        if (message instanceof org.springframework.amqp.core.Message amqpMessage) {
            return parseJsonCanonical(new String(amqpMessage.getBody()));
        }

        if (message instanceof Map<?, ?> map) {
            // The envelope wraps payload under "data" key
            Object data = ((Map<String, Object>) map).get("data");
            if (data instanceof CanonicalDelivery cd) return cd;
            if (data instanceof String json) {
                CanonicalDelivery fromJson = parseJsonCanonical(json);
                if (fromJson != null) return fromJson;
            }
            if (data instanceof Map<?, ?>) {
                // Jackson should have already deserialized — try again via Jackson
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    Object normalized = normalizeValue(data);
                    return om.convertValue(normalized, CanonicalDelivery.class);
                } catch (Exception e) {
                    log.error("Cannot convert map to CanonicalDelivery: {}", e.getMessage());
                }
            }
            // The message might be the canonical directly (no envelope)
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Object normalized = normalizeValue(map);
                return om.convertValue(normalized, CanonicalDelivery.class);
            } catch (Exception e) {
                log.error("Cannot convert root map to CanonicalDelivery: {}", e.getMessage());
            }
        }
        if (message instanceof String json) {
            return parseJsonCanonical(json);
        }
        return null;
    }

    private CanonicalDelivery parseJsonCanonical(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = om.readValue(json, Object.class);
            // Mapper payload is usually an envelope: { event, timestamp, data }
            Object root = parsed;
            if (parsed instanceof Map<?, ?> m && m.containsKey("data")) {
                root = m.get("data");
            }
            Object normalized = normalizeValue(root);
            return om.convertValue(normalized, CanonicalDelivery.class);
        } catch (Exception e) {
            log.error("Cannot parse JSON canonical delivery: {}", e.getMessage());
            return null;
        }
    }

    private void logRawMessage(Object message) {
        try {
            if (message instanceof org.springframework.amqp.core.Message amqpMessage) {
                String body = new String(amqpMessage.getBody());
                log.warn("Raw AMQP body (truncated): {}", truncate(body));
                return;
            }
            if (message instanceof byte[] bytes) {
                String body = new String(bytes);
                log.warn("Raw byte[] body (truncated): {}", truncate(body));
                return;
            }
            log.warn("Raw message (truncated): {}", truncate(String.valueOf(message)));
        } catch (Exception e) {
            log.warn("Failed to log raw message: {}", e.getMessage());
        }
    }

    private String truncate(String value) {
        if (value == null) return "null";
        int max = 800;
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> cleaned = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                String key = String.valueOf(entry.getKey());
                cleaned.put(key, normalizeValue(entry.getValue()));
            }
            return cleaned;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> cleaned = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                cleaned.add(normalizeValue(item));
            }
            return cleaned;
        }
        if (value instanceof Boolean) {
            return null;
        }
        return value;
    }
}
