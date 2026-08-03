package com.asm.erpadapter.messaging;

import com.asm.erpadapter.config.RabbitMQConfig;
import com.asm.erpadapter.adapter.odoo.FieldResolver;
import com.asm.erpadapter.adapter.odoo.MethodResolver;
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
        String deliveryId = str(cmd.get("deliveryId"));
        String orderId    = str(cmd.get("orderId"));
        String erpOrderId = str(cmd.get("erpOrderId"));
        String pickingRef = str(cmd.get("pickingRef"));

        if (op == null || erpOrderId == null) {
            log.warn("ErpSyncCommandConsumer: missing op/erpOrderId, dropping: {}", cmd);
            return; // ack + drop — not retryable
        }

        try {
            dispatch(op, cmd, txId, deliveryId, orderId, erpOrderId, pickingRef);
        } catch (MethodResolver.MethodResolutionException | FieldResolver.FieldResolutionException e) {
            // STRUCTURAL incompatibility with this tenant's Odoo version — retrying is pointless and
            // only buries the real cause under "ERP sync failed after retries". Report the actual
            // reason once so the admin sees "Odoo 19 does not provide capability X" and can fix it
            // with a version binding / mapping override, then ack the message.
            log.error("ERP sync ABORTED (not retryable) — op={} erpOrderId={} txId={} reason={}",
                    op, erpOrderId, txId, e.getMessage());
            resultPublisher.publishResult(txId, deliveryId, orderId, op, false, null, null, null,
                    e.getMessage(), str(cmd.get("rmaId")));
        }
    }

    private void dispatch(String op, Map<String, Object> cmd, String txId, String deliveryId,
                          String orderId, String erpOrderId, String pickingRef) {

        // Provider resolved per-tenant (TenantContext set by the inbound AMQP post-processor from the
        // X-Company-Id header), never from the command payload.
        ErpSyncPort sync = router.getSync();
        switch (op) {
            case "STOCK_FULL" -> {
                boolean ok = sync.syncFullDelivery(erpOrderId, asInt(cmd.get("backorderPickingId")), txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncFullDelivery returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, deliveryId, orderId, op, true, null, null, null, null);
            }
            case "STOCK_PARTIAL" -> {
                List<ErpPartialItemDTO> items = parseItems(cmd.get("partialItems"));
                ErpPartialDeliveryResultDTO res = sync.syncPartialDelivery(erpOrderId, items, txId, pickingRef);
                if (res == null || !res.isSuccess()) {
                    throw new IllegalStateException("syncPartialDelivery returned false for erpOrderId=" + erpOrderId);
                }
                resultPublisher.publishResult(txId, deliveryId, orderId, op, true,
                        res.getPickingId(), res.getBackorderPickingId(), res.getBackorderBlNumber(), null);
            }
            case "FAILURE" -> {
                boolean ok = sync.syncFailure(erpOrderId, str(cmd.get("failureCode")), str(cmd.get("comment")), txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncFailure returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, deliveryId, orderId, op, true, null, null, null, null);
            }
            case "CANCELLATION" -> {
                boolean ok = sync.syncOrderCancellation(erpOrderId, txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncOrderCancellation returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, deliveryId, orderId, op, true, null, null, null, null);
            }
            case "POD" -> {
                com.asm.erpadapter.dto.ErpPodDTO pod = com.asm.erpadapter.dto.ErpPodDTO.builder()
                        .recipientName(str(cmd.get("recipientName")))
                        .comment(str(cmd.get("comment")))
                        .deliveredAt(str(cmd.get("deliveredAt")))
                        .lat(asDouble(cmd.get("lat")))
                        .lng(asDouble(cmd.get("lng")))
                        // Preferred: MinIO URLs (adapter fetches the bytes); base64 kept as legacy fallback.
                        .bonLivraisonPhotoUrl(str(cmd.get("bonLivraisonPhotoUrl")))
                        .packagePhotoUrl(str(cmd.get("packagePhotoUrl")))
                        .blPhotoBase64(str(cmd.get("blPhotoBase64")))
                        .packagePhotoBase64(str(cmd.get("packagePhotoBase64")))
                        .build();
                boolean ok = sync.syncProofOfDelivery(erpOrderId, pod, txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncProofOfDelivery returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, deliveryId, orderId, op, true, null, null, null, null);
            }
            case "RESCHEDULE" -> {
                boolean ok = sync.syncReschedule(erpOrderId, str(cmd.get("scheduledAt")), txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncReschedule returned false for erpOrderId=" + erpOrderId);
                resultPublisher.publishResult(txId, deliveryId, orderId, op, true, null, null, null, null);
            }
            case "RETURN" -> {
                List<com.asm.erpadapter.dto.ErpReturnItemDTO> returnItems =
                        objectMapper.convertValue(cmd.getOrDefault("returnItems", List.of()),
                                new TypeReference<List<com.asm.erpadapter.dto.ErpReturnItemDTO>>() {});
                boolean ok = sync.syncReturn(erpOrderId, returnItems, str(cmd.get("reason")), txId, pickingRef);
                if (!ok) throw new IllegalStateException("syncReturn returned false for erpOrderId=" + erpOrderId);
                // Echo rmaId so DeliveryService can close the RMA reverse-move loop on the right return.
                resultPublisher.publishResult(txId, deliveryId, orderId, op, true, null, null, null, null, str(cmd.get("rmaId")));
            }
            default -> log.warn("ErpSyncCommandConsumer: unknown op={}, dropping", op);
        }
    }

    /** Terminal failure handler: a command that exhausted retries lands here — tell Delivery it failed. */
    @RabbitListener(queues = RabbitMQConfig.SYNC_DLQ)
    public void onCommandDeadLetter(Map<String, Object> cmd) {
        log.error("ERP sync command dead-lettered after retries — op={} erpOrderId={} txId={}",
                cmd.get("op"), cmd.get("erpOrderId"), cmd.get("txId"));
        resultPublisher.publishResult(str(cmd.get("txId")), str(cmd.get("deliveryId")), str(cmd.get("orderId")),
                str(cmd.get("op")), false, null, null, null, "ERP sync failed after retries", str(cmd.get("rmaId")));
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

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
