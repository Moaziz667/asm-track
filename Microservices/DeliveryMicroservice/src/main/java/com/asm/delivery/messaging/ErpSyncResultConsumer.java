package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.service.EventPublisher;
import com.asm.delivery.service.dispatch.ExceptionResolutionService;
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
    private final ExceptionResolutionService exceptionResolutionService;

    @RabbitListener(queues = RabbitMQConfig.ERP_SYNC_RESULT_QUEUE)
    @Transactional
    public void onResult(Map<String, Object> result) {
        String orderIdStr = str(result.get("orderId"));
        if (orderIdStr == null) {
            log.warn("ErpSyncResultConsumer: result missing orderId, dropping: {}", result);
            return;
        }
        UUID orderId = UUID.fromString(orderIdStr);
        UUID deliveryId = result.get("deliveryId") != null ? UUID.fromString(str(result.get("deliveryId"))) : null;
        String op = str(result.get("op"));
        boolean success = Boolean.TRUE.equals(result.get("success"));

        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("ErpSyncResultConsumer: result for unknown orderId={}, dropping", orderId);
            return;
        }

        if (success) {
            order.setOdooSyncStatus("SYNCED");
            order.setSyncRetryCount(0);
            order.setNextSyncRetryAt(null);
            orderRepo.save(order);
            log.info("ERP sync SYNCED — orderId={} op={}", orderId, op);

            // A partial delivery that left a remainder → Odoo created a backorder picking.
            // Auto-create the backorder shipment (new delivery under the same order) + notify.
            Integer backorderPickingId = asInt(result.get("backorderPickingId"));
            if ("STOCK_PARTIAL".equals(op) && backorderPickingId != null) {
                try {
                    exceptionResolutionService.createBackorderShipment(
                            orderId, backorderPickingId, str(result.get("backorderBlNumber")), deliveryId);
                } catch (Exception e) {
                    log.error("Failed to auto-create backorder shipment for orderId={} backorderPickingId={}: {}",
                            orderId, backorderPickingId, e.getMessage(), e);
                }
            }
        } else {
            order.setOdooSyncStatus("SYNC_FAILED");
            order.setLastSyncOp(op);
            order.setLastSyncError(truncate(str(result.get("errorReason"))));
            orderRepo.save(order);
            log.error("ERP sync SYNC_FAILED — orderId={} op={} reason={}", orderId, op, result.get("errorReason"));
            try {
                eventPublisher.publishErpSyncFailed(order, deliveryId, erpOperationCode(op));
            } catch (Exception e) {
                log.warn("Could not publish erp.sync_failed notification for orderId={}: {}", orderId, e.getMessage());
            }
        }
    }

    private String erpOperationCode(String op) {
        return switch (op == null ? "" : op) {
            case "STOCK_FULL", "STOCK_PARTIAL" -> "STOCK";
            case "FAILURE"                     -> "FAILURE_REPORT";
            case "CANCELLATION"                -> "CANCELLATION";
            default                            -> "SYNC";
        };
    }

    private static String str(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    /** Keep the stored error short — the column is TEXT but the UI only shows a snippet. */
    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
