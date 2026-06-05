package com.asm.erpadapter.messaging;

import com.asm.erpadapter.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes the outcome of an ERP sync command back to DeliveryService over
 * {@code erp.sync.result.exchange}, closing the async loop so Delivery can flip the order to
 * SYNCED (storing pickingId/backorderPickingId) or SYNC_FAILED (+ admin notification).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpSyncResultPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishResult(String txId, String orderId, String op, boolean success,
                              Integer pickingId, Integer backorderPickingId, String errorReason) {
        Map<String, Object> result = new HashMap<>();
        result.put("txId", txId);
        result.put("orderId", orderId);
        result.put("op", op);
        result.put("success", success);
        if (pickingId != null) result.put("pickingId", pickingId);
        if (backorderPickingId != null) result.put("backorderPickingId", backorderPickingId);
        if (errorReason != null) result.put("errorReason", errorReason);
        result.put("timestamp", Instant.now().toString());
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.RESULT_EXCHANGE, RabbitMQConfig.RESULT_ROUTING_KEY, result);
        } catch (Exception e) {
            log.error("Failed to publish erp.sync.result txId={} orderId={} success={}: {}",
                    txId, orderId, success, e.getMessage());
        }
    }
}
