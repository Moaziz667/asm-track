package com.asm.delivery.odoo.sync;

import com.asm.delivery.entity.Order;
import com.asm.delivery.odoo.OdooClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Handles synchronizing a FAILED delivery outcome to Odoo:
 * <p>
 * 1. Sales Order page (sale.order): Posts a note in the chatter via message_post with the failure reason and comment.
 * 2. Transfer page: Nothing touched. The picking stays assigned, stock stays reserved.
 * <p>
 * Does not touch Invoice or Products pages.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FailedDeliverySync {

    private final OdooClient odooClient;

    public boolean sync(Order order, Integer erpId, String failureCode, String comment) {
        Instant start = Instant.now();
        log.info("SYNC_START orderId={} erpOrderId={} mode=FAILED", order.getId(), erpId);

        String note = "Delivery failed: "
                + (failureCode != null ? failureCode : "UNKNOWN")
                + (comment != null && !comment.isBlank() ? " - " + comment : "");

        try {
            boolean success = odooClient.addNoteToSaleOrder(erpId, note);
            log.info("SYNC_DONE orderId={} erpOrderId={} mode=FAILED success={} durationMs={}", 
                    order.getId(), erpId, success, Duration.between(start, Instant.now()).toMillis());
            return success;
        } catch (Exception e) {
            log.error("SYNC_FAIL orderId={} erpOrderId={} mode=FAILED exception", order.getId(), erpId, e);
            return false;
        }
    }
}
