package com.asm.erpadapter.port;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.dto.ErpPodDTO;
import com.asm.erpadapter.dto.ErpReturnItemDTO;

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

    /**
     * Push the proof of delivery (recipient, timestamp, geo + signature/photos) onto
     * the ERP order: photos become ir.attachment records and the metadata is posted to
     * the chatter. Idempotent on {@code transactionId}.
     */
    boolean syncProofOfDelivery(String erpOrderId, ErpPodDTO pod, String transactionId, String pickingRef);

    /**
     * Push a customer return (RMA) onto the ERP order: records the returned lines on the chatter
     * and creates a reverse stock move for resellable items. Idempotent on {@code transactionId}.
     */
    boolean syncReturn(String erpOrderId, List<ErpReturnItemDTO> items, String reason, String transactionId, String pickingRef);

    /**
     * Push a new committed delivery date onto the ERP order after a re-plan, so the ERP's promised
     * date matches the delivery platform. Idempotent on {@code transactionId}. Default no-op for
     * providers that do not track a delivery date.
     *
     * @param scheduledAt ISO-8601 date/time string of the new commitment
     */
    default boolean syncReschedule(String erpOrderId, String scheduledAt, String transactionId, String pickingRef) {
        return true;
    }

    /**
     * Create + validate an invoice in the ERP for a delivered order. <b>Admin-triggered and synchronous</b>
     * (not part of the delivery outbox): the operator clicks "create invoice" on a delivery and gets the
     * ERP invoice reference back immediately. ASM never computes anything — the ERP prices/taxes/accounts
     * the invoice from its own config. Bills the DELIVERED quantity (ERPNext from the DN, Odoo via the
     * delivered-qty invoicing policy). Returns the ERP invoice reference (e.g. "ACC-SINV-2026-00001") or
     * {@code null} on failure/unsupported. Default: unsupported.
     *
     * @param erpOrderId the sale-order reference to invoice
     * @param pickingRef the delivery-note / picking reference, when the invoice should bill a specific shipment
     */
    default String createInvoice(String erpOrderId, String pickingRef) {
        return null;
    }
}
