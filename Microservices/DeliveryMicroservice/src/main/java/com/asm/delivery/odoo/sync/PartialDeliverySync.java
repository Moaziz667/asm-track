package com.asm.delivery.odoo.sync;

import com.asm.delivery.entity.Order;
import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.odoo.OdooClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Handles synchronizing a PARTIAL delivery outcome to Odoo:
 * <p>
 * 1. Transfer page (stock.picking): Finds picking, sets quantity_done on stock.move ONLY for delivered items, calls validate.
 *    Processes the backorder confirmation wizard to create a new backorder picking for remaining items (assigned -> done for original, assigned for new).
 * 2. Sales Order page (sale.order.line): Sets qty_delivered only for what was delivered.
 * <p>
 * Does not touch Invoice or Products pages.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartialDeliverySync {

    private final OdooClient odooClient;

    public boolean sync(Order order, Integer erpId, List<PartialDeliveryItem> partialItems) {
        Instant start = Instant.now();
        log.info("SYNC_START orderId={} erpOrderId={} mode=PARTIAL items={}",
                order.getId(), erpId, partialItems != null ? partialItems.size() : 0);

        try {
            // Step 1: Validate partial transfer (creates backorder)
            OdooClient.PartialTransferResult transferResult = odooClient.validatePartialTransfer(erpId, partialItems);
            boolean transferOk = transferResult != null && transferResult.success();
            Integer backorderId = transferResult != null ? transferResult.backorderPickingId() : null;

            log.info("SYNC_TRANSFER orderId={} erpOrderId={} mode=PARTIAL success={} pickingId={} backorderId={}",
                    order.getId(), erpId, transferOk,
                    transferResult != null ? transferResult.pickingId() : null,
                    backorderId);

            if (transferOk) {
                // Keep track of the newly created backorder for future deliveries
                order.setOdooBackorderId(backorderId);

                // Step 2: Set qty_delivered on sales order lines (partial)
                boolean deliveredSynced = odooClient.syncSaleOrderLineDeliveredQuantities(erpId, partialItems, false);
                log.info("SYNC_DELIVERED_LINES orderId={} erpOrderId={} mode=PARTIAL success={}",
                        order.getId(), erpId, deliveredSynced);
                
                log.info("SYNC_DONE orderId={} erpOrderId={} mode=PARTIAL status=SYNCED durationMs={}",
                        order.getId(), erpId, Duration.between(start, Instant.now()).toMillis());
                return true;
            } else {
                log.error("SYNC_TRANSFER_FAILED orderId={} erpOrderId={} mode=PARTIAL items={} reason=VALIDATE_PARTIAL_TRANSFER_FAILED",
                        order.getId(), erpId, partialItems != null ? partialItems.size() : 0);
                return false;
            }
        } catch (Exception e) {
            log.error("SYNC_FAIL orderId={} erpOrderId={} mode=PARTIAL exception", order.getId(), erpId, e);
            return false;
        }
    }
}
