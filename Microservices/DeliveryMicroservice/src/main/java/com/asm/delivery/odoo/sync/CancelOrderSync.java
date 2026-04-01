package com.asm.delivery.odoo.sync;

import com.asm.delivery.entity.Order;
import com.asm.delivery.odoo.OdooClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Handles synchronizing an ANNULÉE (cancelled) outcome to Odoo:
 * <p>
 * 1. Sales Order page (sale.order): Call action_cancel on the sale order, handling cancellation wizard appropriately.
 * 2. Transfer page: Odoo automatically cancels the linked picking. We do not need to intervene.
 * <p>
 * Does not touch Invoice or Products pages.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelOrderSync {

    private final OdooClient odooClient;

    public boolean sync(Order order, Integer erpId) {
        Instant start = Instant.now();
        log.info("SYNC_START orderId={} erpOrderId={} mode=CANCEL", order.getId(), erpId);

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            if (attempts > 0) {
                try { 
                    Thread.sleep(1000); 
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            success = odooClient.cancelSaleOrder(erpId);
            attempts++;
        }

        if (success) {
            log.info("SYNC_DONE orderId={} erpOrderId={} mode=CANCEL status=SYNCED durationMs={}",
                    order.getId(), erpId, Duration.between(start, Instant.now()).toMillis());
            return true;
        } else {
            log.warn("SYNC_FAIL orderId={} erpOrderId={} mode=CANCEL attempts={} status=PENDING_CANCEL",
                    order.getId(), erpId, attempts);
            return false;
        }
    }
}
