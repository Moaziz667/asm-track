package com.asm.erpadapter.messaging;

import com.asm.erpadapter.config.RabbitMQConfig;
import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.routing.ErpProviderRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Consumes ERP sync commands from {@code erp.sync.command.queue}, applies them to the ERP via the
 * provider's {@link ErpSyncPort} (idempotent on {@code txId}), and publishes the result back.
 *
 * <p>A non-success outcome throws so the container retries; once retries are exhausted the message
 * dead-letters to {@code erp.sync.command.dlq}, where {@link #onCommandDeadLetter} publishes a
 * FAILED result so DeliveryService flips the order to SYNC_FAILED and notifies the admin (the order
 * never stays stuck on "Syncing…").
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpSyncCommandConsumer {

    private final ErpProviderRouter router;
    private final ErpSyncResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.SYNC_QUEUE)
    public void onCommand(Map<String, Object> cmd) {
        String op         = str(cmd.get("op"));
        String txId       = str(cmd.get("txId"));
        String orderId    = str(cmd.get("orderId"));
        String provider   = cmd.get("erpProvider") != null ? str(cmd.get("erpProvider")) : "odoo";
        String erpOrderId = str(cmd.get("erpOrderId"));
        String pickingRef = str(cmd.get("pickingRef"));

        if (op == null || erpOrderId == null) {
            log.warn("ErpSyncCommandConsumer: missing op/erpOrderId, dropping: {}", cmd);
            return; // ack + drop — not retryable
        }

        ErpSyncPort sync = router.getSync(provider);
        switch (op) {
            case "STOCK_FULL" -> {
                boolean ok = sync.syncFullDelivery(erpOrderId, asInt(cmd.get("backorderPickingId")), txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncFullDelivery returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, orderId, op, true, null, null, null);
            }
            case "STOCK_PARTIAL" -> {
                List<ErpPartialItemDTO> items = parseItems(cmd.get("partialItems"));
                ErpPartialDeliveryResultDTO res = sync.syncPartialDelivery(erpOrderId, items, txId, pickingRef);
                if (res == null || !res.isSuccess()) {
                    throw new IllegalStateException("syncPartialDelivery returned false for erpOrderId=" + erpOrderId);
                }
                resultPublisher.publishResult(txId, orderId, op, true, res.getPickingId(), res.getBackorderPickingId(), null);
            }
            case "FAILURE" -> {
                boolean ok = sync.syncFailure(erpOrderId, str(cmd.get("failureCode")), str(cmd.get("comment")), txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncFailure returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, orderId, op, true, null, null, null);
            }
            case "CANCELLATION" -> {
                boolean ok = sync.syncOrderCancellation(erpOrderId, txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncOrderCancellation returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, orderId, op, true, null, null, null);
            }
            default -> log.warn("ErpSyncCommandConsumer: unknown op={}, dropping", op);
        }
    }

    /** Terminal failure handler: a command that exhausted retries lands here — tell Delivery it failed. */
    @RabbitListener(queues = RabbitMQConfig.SYNC_DLQ)
    public void onCommandDeadLetter(Map<String, Object> cmd) {
        log.error("ERP sync command dead-lettered after retries — op={} erpOrderId={} txId={}",
                cmd.get("op"), cmd.get("erpOrderId"), cmd.get("txId"));
        resultPublisher.publishResult(str(cmd.get("txId")), str(cmd.get("orderId")), str(cmd.get("op")),
                false, null, null, "ERP sync failed after retries");
    }

    private List<ErpPartialItemDTO> parseItems(Object raw) {
        if (raw == null) return List.of();
        return objectMapper.convertValue(raw, new TypeReference<List<ErpPartialItemDTO>>() {});
    }

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
