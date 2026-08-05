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
     * The delivery-note number this provider ended up putting on the shipment, asked <em>after</em> a
     * delivery has synced.
     *
     * <p>Most ERPs hand ASM that number at import: Odoo's outgoing picking exists, named, from the
     * moment the sale order is confirmed, so it is stored on the order and nothing needs asking.
     * ERPNext does not model a shipment until one happens — the Delivery Note is created at delivery
     * time — so its number cannot exist at import, and without this the number the adapter had in
     * hand went to the log and no further. The consequence was visible: "télécharger le bon de
     * livraison" addressed a document by a reference ASM had never kept, so on an ERPNext tenant the
     * button could only ever fail, on deliveries whose note the ERP was holding all along.
     *
     * <p>Default null — "I already told you at import". Only a provider that discovers the number
     * late has anything to add here.
     */
    default String deliveredBlNumber(String erpOrderId) { return null; }

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

    /**
     * Fetch the rendered PDF of an ERP invoice by its reference (e.g. "ACC-SINV-2026-00007"). Synchronous,
     * admin-triggered download. Returns the raw PDF bytes or {@code null} on failure/unsupported.
     */
    default byte[] getInvoicePdf(String invoiceRef) {
        return null;
    }

    /**
     * Fetch the delivery note (bon de livraison) <b>as the ERP renders it</b>, by picking / delivery-note
     * reference (e.g. {@code "WH/OUT/00326"}).
     *
     * <p>The delivery note is a fiscal document: it carries the issuer's tax identity, the uninterrupted
     * numbering series, lot/serial traceability and whatever legal mentions the tenant's accountant added
     * to their own report. ASM re-rendering it from synced data reproduced none of that, so the document
     * is taken from the system that owns it — the same rule already applied to invoices by
     * {@link #createInvoice} and {@link #getInvoicePdf}.
     *
     * @return raw PDF bytes, or {@code null} on failure/unsupported.
     */
    default byte[] getDeliveryNotePdf(String pickingRef) {
        return null;
    }
}
