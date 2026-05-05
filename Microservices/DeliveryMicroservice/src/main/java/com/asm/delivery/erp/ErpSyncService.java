package com.asm.delivery.erp;

import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.entity.Order;
import com.asm.delivery.erp.client.ErpAdapterClient;
import com.asm.delivery.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates ERP synchronization for delivery lifecycle events.
 *
 * <p>Delegates all actual ERP communication to {@link ErpAdapterClient}, which calls
 * the standalone {@code ErpAdapterService}. This service owns the retry state machine:
 * it marks orders as {@code PENDING_RETRY} or {@code PENDING_CANCEL} when the adapter
 * is unreachable, and computes exponential backoff timestamps for the scheduler.
 *
 * <p><b>Sync status values on {@code Order}:</b>
 * <ul>
 *   <li>{@code SYNCED}        — successfully pushed to ERP</li>
 *   <li>{@code PENDING_RETRY} — delivery/cancel sync failed; will retry</li>
 *   <li>{@code PENDING_CANCEL} — cancellation sync failed; will retry</li>
 *   <li>{@code SYNC_FAILED}   — exceeded max retry count; needs manual review</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpSyncService {

    private static final String SYNCED         = "SYNCED";
    private static final String PENDING_RETRY  = "PENDING_RETRY";
    private static final String PENDING_CANCEL = "PENDING_CANCEL";
    private static final String SYNC_FAILED    = "SYNC_FAILED";

    /** Maximum retry attempts before marking an order as permanently failed. */
    @Value("${erp.sync.retry.max-retries:10}")
    private int maxRetries;

    private final ErpAdapterClient erpAdapterClient;
    private final OrderRepository  orderRepo;

    // ══════════════════════════════════════════════════════════════════════════
    //  1. Order Cancellation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Push an order cancellation to the ERP.
     *
     * <p>On failure, marks the order as {@code PENDING_CANCEL} with exponential
     * backoff so the {@link ErpSyncRetryScheduler} will retry.
     *
     * @param order the order to cancel; must have a non-null {@code erpOrderId}
     */
    public void syncOrderCancellation(Order order) {
        if (order.getErpOrderId() == null) {
            log.debug("syncOrderCancellation: skipped — no erpOrderId for orderId={}", order.getId());
            return;
        }

        boolean success = erpAdapterClient.syncOrderCancellation(order.getErpOrderId(), null);
        if (success) {
            markSynced(order);
            log.info("syncOrderCancellation: success orderId={} erpOrderId={}", order.getId(), order.getErpOrderId());
        } else {
            markForRetry(order, PENDING_CANCEL);
            log.warn("syncOrderCancellation: failed — scheduled for retry orderId={} attempt={}",
                    order.getId(), order.getSyncRetryCount());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  2. Full Delivery
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Push a full delivery outcome to the ERP (all items delivered).
     *
     * <p>The ERP adapter validates the stock transfer and marks all sale order
     * lines as fully delivered. On failure, schedules retry.
     *
     * @param order the delivered order; must have a non-null {@code erpOrderId}
     */
    public void syncStockUpdate(Order order) {
        if (order.getErpOrderId() == null) {
            log.debug("syncStockUpdate: skipped — no erpOrderId for orderId={}", order.getId());
            return;
        }

        boolean success = erpAdapterClient.syncFullDelivery(
                order.getErpOrderId(), order.getOdooBackorderId(), null);
        if (success) {
            markSynced(order);
            log.info("syncStockUpdate: success orderId={} erpOrderId={}", order.getId(), order.getErpOrderId());
        } else {
            markForRetry(order, PENDING_RETRY);
            log.warn("syncStockUpdate: failed — scheduled for retry orderId={} attempt={}",
                    order.getId(), order.getSyncRetryCount());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  3. Partial Delivery
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Push a partial delivery outcome to the ERP.
     *
     * <p>The ERP adapter validates the partial stock transfer, creates a backorder
     * for remaining items, and returns the backorder picking ID which is stored
     * on the order for the next delivery attempt.
     *
     * @param order        the partially delivered order
     * @param partialItems items with their actually delivered quantities
     */
    public void syncPartialStockUpdate(Order order, List<PartialDeliveryItem> partialItems) {
        if (order.getErpOrderId() == null) {
            log.debug("syncPartialStockUpdate: skipped — no erpOrderId for orderId={}", order.getId());
            return;
        }

        Map<String, Object> result = erpAdapterClient.syncPartialDelivery(
                order.getErpOrderId(), partialItems, null);
        boolean success = Boolean.TRUE.equals(result.get("success"));

        if (success) {
            // Store backorder picking ID for future delivery attempts
            Object backorderId = result.get("backorderPickingId");
            if (backorderId instanceof Integer bi) {
                order.setOdooBackorderId(bi);
            } else if (backorderId instanceof String bs) {
                order.setOdooBackorderId(parseIntSafe(bs));
            }
            markSynced(order);
            log.info("syncPartialStockUpdate: success orderId={} backorderId={}", order.getId(), order.getOdooBackorderId());
        } else {
            markForRetry(order, PENDING_RETRY);
            log.warn("syncPartialStockUpdate: failed — scheduled for retry orderId={} attempt={}",
                    order.getId(), order.getSyncRetryCount());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  4. Failure Note
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Push a failure note to the ERP when delivery could not be completed.
     *
     * <p>This is a best-effort notification. If it fails, it is still retried
     * via the scheduler so the ERP always gets the failure reason.
     *
     * @param order       the order that failed delivery
     * @param failureCode reason code (e.g. {@code "CLIENT_ABSENT"})
     * @param comment     optional free-text from the driver
     */
    public void syncFailure(Order order, String failureCode, String comment) {
        if (order.getErpOrderId() == null) {
            log.debug("syncFailure: skipped — no erpOrderId for orderId={}", order.getId());
            return;
        }

        boolean success = erpAdapterClient.syncFailure(order.getErpOrderId(), failureCode, comment, null);
        if (success) {
            markSynced(order);
            log.info("syncFailure: success orderId={} failureCode={}", order.getId(), failureCode);
        } else {
            markForRetry(order, PENDING_RETRY);
            log.warn("syncFailure: failed — scheduled for retry orderId={} attempt={}",
                    order.getId(), order.getSyncRetryCount());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Retry State Machine
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Mark an order as successfully synced and reset retry counters.
     */
    private void markSynced(Order order) {
        order.setOdooSyncStatus(SYNCED);
        order.setSyncRetryCount(0);
        order.setNextSyncRetryAt(null);
        orderRepo.save(order);
    }

    /**
     * Mark an order for retry using exponential backoff.
     *
     * <p>Backoff schedule (minutes): 1, 2, 4, 8, 16, 32, 64, 128, 240, 240, ...
     * After {@code maxRetries} attempts, permanently marks as {@code SYNC_FAILED}.
     *
     * @param order  the order that failed sync
     * @param status either {@code PENDING_RETRY} or {@code PENDING_CANCEL}
     */
    void markForRetry(Order order, String status) {
        int currentCount = order.getSyncRetryCount() != null ? order.getSyncRetryCount() : 0;

        if (currentCount >= maxRetries) {
            order.setOdooSyncStatus(SYNC_FAILED);
            log.error("syncRetry: SYNC_FAILED — orderId={} exceeded {} max retries. Manual intervention required.",
                    order.getId(), maxRetries);
        } else {
            // Exponential backoff: 2^n minutes, capped at 4 hours
            long delayMinutes = Math.min((long) Math.pow(2, currentCount), 240);
            order.setOdooSyncStatus(status);
            order.setSyncRetryCount(currentCount + 1);
            order.setNextSyncRetryAt(LocalDateTime.now().plusMinutes(delayMinutes));
            log.debug("syncRetry: orderId={} scheduled retry #{} in {}min at {}",
                    order.getId(), currentCount + 1, delayMinutes, order.getNextSyncRetryAt());
        }
        orderRepo.save(order);
    }

    private static Integer parseIntSafe(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
