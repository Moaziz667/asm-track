package com.asm.delivery.erp;

import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderStatus;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled retry processor for failed ERP synchronization attempts.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Every 2 minutes (configurable), queries for orders with status
 *       {@code PENDING_RETRY} or {@code PENDING_CANCEL} whose backoff
 *       window has elapsed ({@code nextSyncRetryAt <= now})</li>
 *   <li>For each order, re-dispatches the appropriate sync operation based
 *       on the order's delivery status</li>
 *   <li>{@link ErpSyncService} manages the retry counter and next backoff
 *       timestamp on each attempt</li>
 *   <li>After {@code erp.sync.retry.max-retries} failures (default 10),
 *       the order is permanently marked {@code SYNC_FAILED}</li>
 * </ol>
 *
 * <h2>Exponential Backoff Schedule</h2>
 * <pre>
 *   Attempt 1: retry in  1 min
 *   Attempt 2: retry in  2 min
 *   Attempt 3: retry in  4 min
 *   Attempt 4: retry in  8 min
 *   Attempt 5: retry in 16 min
 *   Attempt 6: retry in 32 min
 *   Attempt 7: retry in 64 min
 *   Attempt 8+: retry in 4 hours (max cap)
 * </pre>
 *
 * <h2>Failure Isolation</h2>
 * Each order is processed in its own transaction. A failure on one order
 * does not affect others in the same batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpSyncRetryScheduler {

    @Value("${erp.sync.retry.max-retries:10}")
    private int maxRetries;

    private final OrderRepository     orderRepo;
    private final DeliveryRepository  deliveryRepo;
    private final ErpSyncService      erpSyncService;

    /**
     * Main retry loop. Runs every 2 minutes (configurable via
     * {@code erp.sync.retry.scheduler-delay-ms}).
     *
     * <p>Processes up to 50 orders per run to bound execution time.
     * Orders are sorted by {@code nextSyncRetryAt ASC} so oldest pending
     * retries are attempted first.
     */
    @Scheduled(fixedDelayString = "${erp.sync.retry.scheduler-delay-ms:120000}",
               timeUnit = TimeUnit.MILLISECONDS)
    public void retryPendingSyncs() {
        List<Order> pending = orderRepo.findOrdersPendingSync(LocalDateTime.now(), maxRetries);
        if (pending.isEmpty()) {
            log.trace("retryPendingSyncs: no orders pending sync");
            return;
        }

        log.info("retryPendingSyncs: processing {} orders", pending.size());

        int retried  = 0;
        int skipped  = 0;

        for (Order order : pending) {
            try {
                boolean dispatched = retrySingleOrder(order);
                if (dispatched) retried++;
                else skipped++;
            } catch (Exception e) {
                // Isolate per-order failures — log and continue
                log.error("retryPendingSyncs: unexpected error for orderId={}", order.getId(), e);
            }
        }

        log.info("retryPendingSyncs: done — retried={} skipped={}", retried, skipped);
    }

    /**
     * Retry the ERP sync for a single order in its own transaction.
     *
     * <p>The sync operation to retry is determined by the order's current
     * {@code odooSyncStatus}:
     * <ul>
     *   <li>{@code PENDING_CANCEL} → re-attempt cancellation sync</li>
     *   <li>{@code PENDING_RETRY}  → re-attempt delivery sync (full, partial, or failure note)
     *       based on the order's delivery status from the delivery table</li>
     * </ul>
     *
     * @param order the order to retry
     * @return true if a sync was dispatched, false if the order was skipped
     */
    @Transactional
    public boolean retrySingleOrder(Order order) {
        String syncStatus = order.getOdooSyncStatus();

        // Re-attempt cancellation
        if ("PENDING_CANCEL".equals(syncStatus)) {
            log.info("retrySync: CANCEL orderId={} attempt={}", order.getId(), order.getSyncRetryCount());
            erpSyncService.syncOrderCancellation(order);
            return true;
        }

        // Re-attempt delivery sync — choose operation based on order delivery status
        if ("PENDING_RETRY".equals(syncStatus)) {
            OrderStatus status = order.getStatus();

            if (status == OrderStatus.DELIVERED) {
                log.info("retrySync: FULL_DELIVERY orderId={} attempt={}", order.getId(), order.getSyncRetryCount());
                erpSyncService.syncStockUpdate(order);
                return true;
            } else if (status == OrderStatus.PARTIALLY_DELIVERED) {
                // For partial retry, we can't recover the original partial items list,
                // so we retry as a full delivery of whatever remains (using backorder picking).
                log.info("retrySync: PARTIAL_AS_FULL orderId={} backorderId={} attempt={}",
                        order.getId(), order.getOdooBackorderId(), order.getSyncRetryCount());
                erpSyncService.syncStockUpdate(order);
                return true;
            } else {
                // By elimination, if it failed in PENDING_RETRY and the order isn't successfully delivered,
                // it means it was a failure note sync (driver failed delivery attempt).
                log.info("retrySync: FAILURE_NOTE orderId={} attempt={}", order.getId(), order.getSyncRetryCount());
                erpSyncService.syncFailure(order, "DELIVERY_FAILED", "Retry after previous failure note failed");
                return true;
            }
        }

        log.warn("retrySync: unexpected syncStatus={} for orderId={} — skipping", syncStatus, order.getId());
        return false;
    }
}
