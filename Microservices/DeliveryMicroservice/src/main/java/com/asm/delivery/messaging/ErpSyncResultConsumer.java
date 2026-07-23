package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Closes the async ERP-sync loop: consumes {@code erp.sync.result} events and applies the outcome to
 * the order — SYNCED, or SYNC_FAILED (+ admin notification so nothing stays stuck on "Syncing…").
 * On a successful PARTIAL delivery that produced a backorder picking, it auto-creates the backorder
 * as a new shipment under the same order (no cloned order) and notifies admins. Idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpSyncResultConsumer {

    private final OrderRepository orderRepo;
    private final EventPublisher eventPublisher;
    private final com.asm.delivery.repository.RmaRepository rmaRepo;

    @RabbitListener(queues = RabbitMQConfig.ERP_SYNC_RESULT_QUEUE)
    @Transactional
    public void onResult(Map<String, Object> result) {
        String op = str(result.get("op"));
        boolean ok = Boolean.TRUE.equals(result.get("success"));

        // D2 — A RETURN (RMA) result is about a return, not an order: route it to the RMA branch and
        // close the reverse-stock-move loop. Idempotent: re-applying the same terminal state is a no-op.
        if ("RETURN".equals(op)) {
            applyReturnResult(result, ok);
            return;
        }

        String orderIdStr = str(result.get("orderId"));
        if (orderIdStr == null) {
            log.warn("ErpSyncResultConsumer: result missing orderId, dropping: {}", result);
            return;
        }
        UUID orderId = UUID.fromString(orderIdStr);
        UUID deliveryId = result.get("deliveryId") != null ? UUID.fromString(str(result.get("deliveryId"))) : null;

        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("ErpSyncResultConsumer: result for unknown orderId={}, dropping", orderId);
            return;
        }

        if (ok) {
            order.setErpSyncStatus("SYNCED");
            order.setSyncRetryCount(0);
            order.setNextSyncRetryAt(null);
            orderRepo.save(order);
            log.info("ERP sync SYNCED — orderId={} op={}", orderId, op);
            // Backorders are NOT auto-created as shipments. When a partial delivery syncs, Odoo creates
            // the backorder picking; the operator imports it from the Import page (per-BL import), where
            // it becomes its own order — the same path as every other Odoo picking. Single source of
            // truth, and no duplicate (auto-shipment + importable picking) for the same backorder.
        } else {
            order.setErpSyncStatus("SYNC_FAILED");
            order.setLastSyncOp(op);
            order.setLastSyncError(truncate(str(result.get("errorReason"))));
            orderRepo.save(order);
            log.error("ERP sync SYNC_FAILED — orderId={} op={} reason={}", orderId, op, result.get("errorReason"));
            try {
                eventPublisher.publishErpSyncFailed(order, deliveryId, com.asm.delivery.service.ErpNotificationLabel.of(op));
            } catch (Exception e) {
                log.warn("Could not publish erp.sync_failed notification for orderId={}: {}", orderId, e.getMessage());
            }
        }
    }

    /**
     * D2 — Closes the RMA reverse-stock-move loop. The RETURN result carries the {@code rmaId};
     * we flip the return's {@code erpSyncStatus} to SYNCED or SYNC_FAILED so a rejected reverse move
     * never stays silently RESTOCKED. Idempotent: applying the same terminal state twice is a no-op,
     * and an unknown/missing rmaId is logged and dropped rather than throwing (avoids a poison message).
     */
    private void applyReturnResult(Map<String, Object> result, boolean ok) {
        String rmaIdStr = str(result.get("rmaId"));
        if (rmaIdStr == null) {
            log.warn("ErpSyncResultConsumer: RETURN result missing rmaId, dropping: {}", result);
            return;
        }
        UUID rmaId = UUID.fromString(rmaIdStr);
        rmaRepo.findById(rmaId).ifPresentOrElse(rma -> {
            if (ok) {
                rma.setErpSyncStatus("SYNCED");
                rma.setErpSyncError(null);
                log.info("RMA reverse move SYNCED — rmaId={}", rmaId);
            } else {
                rma.setErpSyncStatus("SYNC_FAILED");
                rma.setErpSyncError(truncate(str(result.get("errorReason"))));
                log.error("RMA reverse move SYNC_FAILED — rmaId={} reason={}", rmaId, result.get("errorReason"));
            }
            rmaRepo.save(rma);
        }, () -> log.warn("ErpSyncResultConsumer: RETURN result for unknown rmaId={}, dropping", rmaId));
    }

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    /** Keep the stored error short — the column is TEXT but the UI only shows a snippet. */
    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

}
