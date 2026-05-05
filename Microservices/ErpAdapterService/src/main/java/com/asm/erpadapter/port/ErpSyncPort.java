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
 * a delivery outcome is recorded in the delivery platform:
 * <ol>
 *   <li>Order cancellation</li>
 *   <li>Full delivery (all items delivered)</li>
 *   <li>Partial delivery (some items delivered, backorder created)</li>
 *   <li>Failed delivery (driver couldn't deliver, note posted)</li>
 * </ol>
 *
 * <h2>Adding a New ERP Provider</h2>
 * <pre>
 *  1. Create package: adapter/{erpname}/
 *  2. Create class:   {ErpName}SyncAdapter implements ErpSyncPort
 *  3. Annotate with:  @Component("{erpname}")   ← must match erpProvider param
 *  4. Implement all four methods below
 *  5. The ErpProviderRouter auto-discovers it — no config changes needed
 * </pre>
 *
 * <h2>Implementation Contract</h2>
 * <ul>
 *   <li><b>Idempotent</b> — calling the same method twice must not corrupt ERP state</li>
 *   <li><b>Self-contained errors</b> — catch all ERP exceptions internally; return
 *       {@code false}/{@link ErpPartialDeliveryResultDTO#success}={@code false} on failure</li>
 *   <li><b>No partner creation</b> — clients already exist in the ERP; never create them</li>
 *   <li><b>No state assumptions</b> — methods may be called during retry; the ERP may
 *       already be in the expected state; check before mutating</li>
 * </ul>
 */
public interface ErpSyncPort {

    /**
     * Cancel a confirmed sale order in the ERP.
     *
     * <p>The implementation must leave the ERP order in a cancelled state.
     * If the ERP requires wizard steps (confirmation dialogs), they must be
     * handled transparently within this method.
     *
     * <p><b>Idempotency:</b> If the order is already cancelled, return {@code true}.
     *
     * @param erpOrderId  ERP-side order reference (e.g. Odoo numeric ID or SO name like "S00042")
     * @return {@code true} if the order is now cancelled in the ERP
     *
     * @implSpec The ERP may require multi-step confirmation wizards.
     *           Retry up to 3 times internally for wizard-related flakiness.
     */
    boolean syncOrderCancellation(String erpOrderId);

    /**
     * Validate the stock transfer for a fully delivered order.
     *
     * <p>All items were delivered. The implementation must:
     * <ol>
     *   <li>Locate the pending stock transfer (picking) linked to the sale order</li>
     *   <li>Set all move quantities to done</li>
     *   <li>Validate (confirm) the transfer</li>
     *   <li>Update delivered quantities on the sale order lines</li>
     * </ol>
     *
     * <p><b>Idempotency:</b> If the transfer is already validated (state = "done"), return {@code true}.
     *
     * @param erpOrderId         ERP-side order reference
     * @param backorderPickingId picking ID from a prior partial delivery; pass {@code null} for first delivery
     * @return {@code true} if transfer is now validated and quantities are synced
     *
     * @implSpec Uses Odoo's {@code stock.picking.button_validate} and handles backorder wizards.
     */
    boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId);

    /**
     * Validate a partial stock transfer and create a backorder for remaining items.
     *
     * <p>Some items were delivered. The implementation must:
     * <ol>
     *   <li>Locate the pending stock transfer (picking)</li>
     *   <li>Set each move's {@code qty_done} to the value in {@code items}</li>
     *   <li>Validate the transfer — the ERP should auto-create a backorder</li>
     *   <li>Update delivered quantities on sale order lines</li>
     *   <li>Return the backorder picking ID so it can be stored for future deliveries</li>
     * </ol>
     *
     * <p><b>Idempotency:</b> If the picking is already done, return success with existing backorder ID.
     *
     * @param erpOrderId ERP-side order reference
     * @param items      list of items with their actually delivered quantities;
     *                   items not in this list are considered undelivered
     * @return result containing {@code success}, the validated {@code pickingId},
     *         and the created {@code backorderPickingId} (nullable if none)
     *
     * @implSpec Uses Odoo's backorder confirmation wizard ({@code stock.backorder.confirmation}).
     *           SKU/referenceKey is resolved to Odoo product ID via default_code, barcode, or name.
     */
    ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items);

    /**
     * Post a failure note on the ERP order when delivery could not be completed.
     *
     * <p>The note should be visible to ERP users (e.g. in the order chatter).
     * No stock movements should be made — this is a notification-only operation.
     *
     * <p><b>Idempotency:</b> Posting duplicate notes is acceptable; the ERP should append them.
     *
     * @param erpOrderId  ERP-side order reference
     * @param failureCode reason code (e.g. {@code "CLIENT_ABSENT"}, {@code "ADDRESS_NOT_FOUND"})
     * @param comment     free-text comment from the driver (nullable)
     * @return {@code true} if the note was posted successfully
     *
     * @implSpec Posts to both the {@code note} field of the sale order and the chatter
     *           ({@code message_post}) so it is visible in the Odoo activity log.
     */
    boolean syncFailure(String erpOrderId, String failureCode, String comment);
}
