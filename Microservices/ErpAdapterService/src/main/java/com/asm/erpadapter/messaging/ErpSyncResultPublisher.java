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

    public void publishResult(String txId, String deliveryId, String orderId, String op, boolean success,
                              Integer pickingId, Integer backorderPickingId, String backorderBlNumber, String errorReason) {
        publishResult(txId, deliveryId, orderId, op, success, pickingId, backorderPickingId, backorderBlNumber, errorReason, null);
    }

    /**
     * A successful delivery, carrying the note number the provider gave the shipment.
     *
     * <p>Its own method rather than an eleventh positional argument on a list already ten long: only
     * the two delivery operations have a note number to report, and the seven other call sites would
     * have gained a {@code null} that means nothing to them.
     *
     * <p>{@code blNumber} is null for every ERP that names its shipment up front — Odoo's picking is
     * already stored on the order — and set by the ones that only name it once the delivery has
     * happened, which is ERPNext.
     */
    public void publishDeliveryResult(String txId, String deliveryId, String orderId, String op,
                                      Integer pickingId, Integer backorderPickingId,
                                      String backorderBlNumber, String blNumber) {
        Map<String, Object> extra = new HashMap<>();
        if (blNumber != null && !blNumber.isBlank()) extra.put("blNumber", blNumber);
        publish(txId, deliveryId, orderId, op, true, pickingId, backorderPickingId,
                backorderBlNumber, null, null, extra);
    }

    /**
     * Overload carrying {@code rmaId} for RETURN (RMA) results, so DeliveryService can close the
     * reverse-stock-move loop on the right return. Echoed verbatim from the originating command.
     */
    public void publishResult(String txId, String deliveryId, String orderId, String op, boolean success,
                              Integer pickingId, Integer backorderPickingId, String backorderBlNumber, String errorReason,
                              String rmaId) {
        publish(txId, deliveryId, orderId, op, success, pickingId, backorderPickingId,
                backorderBlNumber, errorReason, rmaId, Map.of());
    }

    private void publish(String txId, String deliveryId, String orderId, String op, boolean success,
                         Integer pickingId, Integer backorderPickingId, String backorderBlNumber,
                         String errorReason, String rmaId, Map<String, Object> extra) {
        Map<String, Object> result = new HashMap<>();
        result.put("txId", txId);
        result.put("deliveryId", deliveryId);
        result.put("orderId", orderId);
        result.put("op", op);
        result.put("success", success);
        if (pickingId != null) result.put("pickingId", pickingId);
        if (backorderPickingId != null) result.put("backorderPickingId", backorderPickingId);
        if (backorderBlNumber != null) result.put("backorderBlNumber", backorderBlNumber);
        if (errorReason != null) result.put("errorReason", errorReason);
        if (rmaId != null) result.put("rmaId", rmaId);
        result.putAll(extra);
        result.put("timestamp", Instant.now().toString());
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.RESULT_EXCHANGE, RabbitMQConfig.RESULT_ROUTING_KEY, result);
        } catch (Exception e) {
            log.error("Failed to publish erp.sync.result txId={} orderId={} success={}: {}",
                    txId, orderId, success, e.getMessage());
        }
    }
}
