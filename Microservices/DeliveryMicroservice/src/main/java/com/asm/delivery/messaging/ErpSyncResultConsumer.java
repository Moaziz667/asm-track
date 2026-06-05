package com.asm.delivery.messaging;

import com.asm.delivery.config.RabbitMQConfig;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.DeliveryRepository;
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
 * Closes the async ERP-sync loop: consumes {@code erp.sync.result} events from ErpAdapter and applies
 * the outcome to the order — SYNCED (storing the backorder picking id) or SYNC_FAILED. On failure it
 * raises the admin {@code erp.sync_failed} notification so the dashboard reflects it (no order stuck
 * on "Syncing…"). Idempotent: re-applying the same outcome is harmless.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpSyncResultConsumer {

    private final OrderRepository orderRepo;
    private final DeliveryRepository deliveryRepo;
    private final EventPublisher eventPublisher;

    @RabbitListener(queues = RabbitMQConfig.ERP_SYNC_RESULT_QUEUE)
    @Transactional
    public void onResult(Map<String, Object> result) {
        String orderIdStr = str(result.get("orderId"));
        if (orderIdStr == null) {
            log.warn("ErpSyncResultConsumer: result missing orderId, dropping: {}", result);
            return;
        }
        UUID orderId = UUID.fromString(orderIdStr);
        String op = str(result.get("op"));
        boolean success = Boolean.TRUE.equals(result.get("success"));

        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("ErpSyncResultConsumer: result for unknown orderId={}, dropping", orderId);
            return;
        }

        if (success) {
            Integer backorderId = asInt(result.get("backorderPickingId"));
            if (backorderId != null) order.setOdooBackorderId(backorderId);
            order.setOdooSyncStatus("SYNCED");
            order.setSyncRetryCount(0);
            order.setNextSyncRetryAt(null);
            orderRepo.save(order);
            log.info("ERP sync SYNCED — orderId={} op={} backorderPickingId={}", orderId, op, backorderId);
        } else {
            order.setOdooSyncStatus("SYNC_FAILED");
            orderRepo.save(order);
            log.error("ERP sync SYNC_FAILED — orderId={} op={} reason={}", orderId, op, result.get("errorReason"));
            try {
                deliveryRepo.findByOrderIdWithOrder(orderId).ifPresent(delivery -> {
                    if (delivery.getOrder() != null) {
                        eventPublisher.publishErpSyncFailed(delivery.getOrder(), delivery.getId(), erpOperationCode(op));
                    }
                });
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

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
