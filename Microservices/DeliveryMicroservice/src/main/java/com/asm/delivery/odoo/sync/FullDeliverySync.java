package com.asm.delivery.odoo.sync;

import com.asm.delivery.entity.Order;
import com.asm.delivery.odoo.OdooClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Handles synchronizing a FULL delivery outcome to Odoo:
 * <p>
 * 1. Transfer page (stock.picking): Finds linked picking, sets quantity_done on stock.move to match planned quantity, then validates (assigned -> done).
 * 2. Sales Order page (sale.order.line): Sets qty_delivered to match planned quantity.
 * <p>
 * Does not touch Invoice or Products pages.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FullDeliverySync {

    private final OdooClient odooClient;

    public boolean sync(Order order, Integer erpId) {
        Instant start = Instant.now();
        Integer backorderPickingId = order.getOdooBackorderId();
        
        log.info("SYNC_START orderId={} erpOrderId={} mode=FULL backorderPickingId={}",
                order.getId(), erpId, backorderPickingId);

        try {
            // Step 1: Validate transfer (stock out)
            boolean transferOk = odooClient.validateTransfer(erpId, backorderPickingId);
            log.info("SYNC_TRANSFER orderId={} erpOrderId={} mode=FULL success={} backorderPickingId={}", 
                    order.getId(), erpId, transferOk, backorderPickingId);
            
            if (transferOk) {
                // Step 2: Set qty_delivered on sales order lines (full)
                boolean deliveredSynced = odooClient.syncSaleOrderLineDeliveredQuantities(erpId, null, true);
                log.info("SYNC_DELIVERED_LINES orderId={} erpOrderId={} mode=FULL success={}",
                        order.getId(), erpId, deliveredSynced);
                
                // Clear the backorder id so we don't accidentally reuse it
                order.setOdooBackorderId(null);
                
                log.info("SYNC_DONE orderId={} erpOrderId={} mode=FULL status=SYNCED durationMs={}",
                        order.getId(), erpId, Duration.between(start, Instant.now()).toMillis());
                return true;
            } else {
                log.error("SYNC_FAIL orderId={} erpOrderId={} mode=FULL phase=TRANSFER", order.getId(), erpId);
                return false;
            }
        } catch (Exception e) {
            log.error("SYNC_FAIL orderId={} erpOrderId={} mode=FULL exception", order.getId(), erpId, e);
            return false;
        }
    }
}
