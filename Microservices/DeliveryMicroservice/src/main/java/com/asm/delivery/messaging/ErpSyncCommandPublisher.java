package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.delivery.dto.request.PartialDeliveryItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes ERP-sync commands (mapped from delivery outcomes) to {@code erp.sync.exchange}.
 * The Outbox guarantees the publish happens once; ErpAdapter applies it to Odoo (idempotent on
 * {@code txId}) and replies with a result event consumed by {@code ErpSyncResultConsumer}.
 * {@code orderId} is carried for result correlation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpSyncCommandPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishStockFull(String deliveryId, String orderId, String erpOrderId, Integer backorderPickingId,
                                 String pickingRef, String txId) {
        Map<String, Object> cmd = base("STOCK_FULL", deliveryId, orderId, erpOrderId, pickingRef, txId);
        if (backorderPickingId != null) cmd.put("backorderPickingId", backorderPickingId);
        send(cmd);
    }

    public void publishStockPartial(String deliveryId, String orderId, String erpOrderId, List<PartialDeliveryItem> items,
                                    String pickingRef, String txId) {
        Map<String, Object> cmd = base("STOCK_PARTIAL", deliveryId, orderId, erpOrderId, pickingRef, txId);
        cmd.put("partialItems", mapItems(items));
        send(cmd);
    }

    public void publishFailure(String deliveryId, String orderId, String erpOrderId, String failureCode, String comment,
                               String pickingRef, String txId) {
        Map<String, Object> cmd = base("FAILURE", deliveryId, orderId, erpOrderId, pickingRef, txId);
        cmd.put("failureCode", failureCode);
        cmd.put("comment", comment);
        send(cmd);
    }

    public void publishCancellation(String deliveryId, String orderId, String erpOrderId, String pickingRef, String txId) {
        send(base("CANCELLATION", deliveryId, orderId, erpOrderId, pickingRef, txId));
    }

    public void publishReschedule(String deliveryId, String orderId, String erpOrderId, String pickingRef, String txId,
                                  String scheduledAt) {
        Map<String, Object> cmd = base("RESCHEDULE", deliveryId, orderId, erpOrderId, pickingRef, txId);
        if (scheduledAt != null) cmd.put("scheduledAt", scheduledAt);
        send(cmd);
    }

    public void publishPod(String deliveryId, String orderId, String erpOrderId, String pickingRef, String txId,
                           Map<String, Object> pod) {
        Map<String, Object> cmd = base("POD", deliveryId, orderId, erpOrderId, pickingRef, txId);
        if (pod != null) pod.forEach((k, v) -> { if (v != null) cmd.put(k, v); });
        send(cmd);
    }

    public void publishReturn(String deliveryId, String orderId, String erpOrderId, String pickingRef, String txId,
                              String reason, String rmaId, List<Map<String, Object>> items) {
        Map<String, Object> cmd = base("RETURN", deliveryId, orderId, erpOrderId, pickingRef, txId);
        if (reason != null) cmd.put("reason", reason);
        // rmaId is echoed back by the adapter on the result so the loop closes on the right RMA (D2).
        if (rmaId != null) cmd.put("rmaId", rmaId);
        cmd.put("returnItems", items != null ? items : new ArrayList<>());
        send(cmd);
    }

    private Map<String, Object> base(String op, String deliveryId, String orderId, String erpOrderId, String pickingRef, String txId) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("op", op);
        cmd.put("txId", txId);
        cmd.put("deliveryId", deliveryId);
        cmd.put("orderId", orderId);
        cmd.put("erpOrderId", erpOrderId);
        if (pickingRef != null) cmd.put("pickingRef", pickingRef);
        return cmd;
    }

    private List<Map<String, Object>> mapItems(List<PartialDeliveryItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items == null) return out;
        for (PartialDeliveryItem item : items) {
            Map<String, Object> m = new HashMap<>();
            m.put("referenceKey", item.referenceKey());
            m.put("quantityDone", item.getQuantityDone());
            if (item.getName() != null)             m.put("itemName", item.getName());
            if (item.getOutcome() != null)          m.put("outcome", item.effectiveOutcome());
            if (item.getReason() != null)           m.put("reason", item.getReason());
            if (item.getReasonLabel() != null)      m.put("reasonLabel", item.getReasonLabel());
            if (item.getComment() != null)          m.put("comment", item.getComment());
            // WMS breakdown: forward the full per-unit disposition segments so the Odoo chatter note
            // can list every outcome (Manquant/Refusé/Endommagé ×qty), not just the dominant one.
            if (item.hasSegments())                 m.put("segments", item.getSegments());
            out.add(m);
        }
        return out;
    }

    private void send(Map<String, Object> cmd) {
        // Allowed to throw: the Outbox treats a publish failure as retryable so the command is never lost.
        rabbitTemplate.convertAndSend(RabbitMQConfig.ERP_SYNC_EXCHANGE, RabbitMQConfig.ERP_SYNC_ROUTING, cmd);
        log.info("Published ERP sync command op={} orderId={} txId={}", cmd.get("op"), cmd.get("orderId"), cmd.get("txId"));
    }
}
