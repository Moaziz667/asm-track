package com.asm.erpadapter.port;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  ErpSyncPort — Order Lifecycle Synchronization Contract
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * <p>Defines the four operations that must be pushed to the ERP whenever
 * a delivery outcome is recorded in the delivery platform.
 *
 * <h2>Implementation Contract</h2>
 * <ul>
 *   <li><b>Idempotent</b> — calling the same method with the same transactionId must not corrupt ERP state</li>
 *   <li><b>Self-contained errors</b> — catch all ERP exceptions internally; return
 *       {@code false}/{@link ErpPartialDeliveryResultDTO#success}={@code false} on failure</li>
 * </ul>
 */
public interface ErpSyncPort {

    /**
     * Cancel a confirmed sale order in the ERP.
     */
    boolean syncOrderCancellation(String erpOrderId, String transactionId, String pickingRef);

    /**
     * Validate the stock transfer for a fully delivered order.
     *
     * @param pickingRef exact delivery-note (picking) number to target; when set it
     *        disambiguates multi-depot orders (several pickings per sale order).
     */
    boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId, String pickingRef);

    /**
     * Validate a partial stock transfer and create a backorder for remaining items.
     *
     * @param pickingRef exact delivery-note (picking) number to target (multi-depot).
     */
    ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items, String transactionId, String pickingRef);

    /**
     * Post a failure note on the ERP order when delivery could not be completed.
     */
    boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId, String pickingRef);
}
