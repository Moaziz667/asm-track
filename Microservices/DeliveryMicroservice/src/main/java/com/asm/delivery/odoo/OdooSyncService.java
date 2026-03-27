package com.asm.delivery.odoo;

import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderSource;
import com.asm.delivery.entity.PaymentType;
import com.asm.delivery.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OdooSyncService {

    private final OdooClient odooClient;
    private final OrderRepository orderRepo;

    // ── Sync order creation to Odoo (APP orders only) ─────────────────────────

    public void syncOrderCreation(Order order) {
        if (order.getSource() != OrderSource.APP) return;
        if (order.getClientOdooPartnerId() == null) return;

        log.info("Syncing order creation to Odoo — orderId={} partnerId={}",
                order.getId(), order.getClientOdooPartnerId());

        // Update partner address with delivery address (best effort)
        odooClient.updatePartnerAddress(
                order.getClientOdooPartnerId(),
                order.getDropoffAddress(),
                order.getDropoffCity()
        );

        Integer odooId = odooClient.createSaleOrder(
                order.getClientOdooPartnerId(),
                order.getItems(),
                order.getDeliveryInstructions()
        );

        if (odooId == null) {
            throw new RuntimeException("Odoo sync failed for orderId=" + order.getId());
        }

        order.setErpOrderId(String.valueOf(odooId));
        orderRepo.save(order);
        log.info("Order creation synced to Odoo — orderId={} erpOrderId={}", order.getId(), odooId);
    }

    // ── Sync order cancellation to Odoo (3 retries, never throws) ────────────

    public void syncOrderCancellation(Order order) {
        if (order.getErpOrderId() == null) return;

        int erpId;
        try {
            erpId = Integer.parseInt(order.getErpOrderId());
        } catch (NumberFormatException e) {
            log.warn("Cannot parse erpOrderId='{}' as integer for orderId={} — skipping Odoo cancel",
                    order.getErpOrderId(), order.getId());
            return;
        }

        log.info("Syncing order cancellation to Odoo — orderId={} erpOrderId={}", order.getId(), erpId);

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            if (attempts > 0) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            success = odooClient.cancelSaleOrder(erpId);
            attempts++;
        }

        if (!success) {
            log.warn("Odoo cancel failed after {} attempts — marking PENDING_CANCEL orderId={}", attempts, order.getId());
            order.setOdooSyncStatus("PENDING_CANCEL");
            orderRepo.save(order);
        } else {
            log.info("Order cancellation synced to Odoo — orderId={} erpOrderId={}", order.getId(), erpId);
        }
    }

    // ── Sync stock update to Odoo: validate transfer → invoice → payment ──────

    public void syncStockUpdate(Order order) {
        if (order.getErpOrderId() == null) return;

        int erpId;
        try {
            erpId = Integer.parseInt(order.getErpOrderId());
        } catch (NumberFormatException e) {
            log.warn("Cannot parse erpOrderId='{}' as integer for orderId={} — skipping stock update",
                    order.getErpOrderId(), order.getId());
            return;
        }

        log.info("Syncing delivery completion to Odoo — orderId={} erpOrderId={} paymentType={}",
                order.getId(), erpId, order.getPaymentType());

        // Step 1: validate transfer (stock out)
        try {
            boolean transferOk = odooClient.validateTransfer(erpId);
            log.info("Odoo validateTransfer — orderId={} erpOrderId={} success={}", order.getId(), erpId, transferOk);
        } catch (Exception e) {
            log.error("Odoo validateTransfer threw unexpectedly — orderId={}: {}", order.getId(), e.getMessage());
        }

        // Step 2: create invoice
        Integer invoiceId = null;
        try {
            invoiceId = odooClient.createInvoice(erpId);
            log.info("Odoo createInvoice — orderId={} erpOrderId={} invoiceId={}", order.getId(), erpId, invoiceId);
        } catch (Exception e) {
            log.error("Odoo createInvoice threw unexpectedly — orderId={}: {}", order.getId(), e.getMessage());
        }

        // Step 3: handle payment based on payment type
        if (invoiceId != null) {
            if (order.getPaymentType() == PaymentType.PREPAID) {
                try {
                    boolean paid = odooClient.registerPayment(invoiceId);
                    log.info("Invoice auto-paid (PREPAID) — orderId={} invoiceId={} success={}", order.getId(), invoiceId, paid);
                } catch (Exception e) {
                    log.error("Odoo registerPayment threw unexpectedly — orderId={}: {}", order.getId(), e.getMessage());
                }
            } else {
                log.info("Invoice left unpaid (COD) - accountant will handle — orderId={} invoiceId={}", order.getId(), invoiceId);
            }
        } else {
            log.warn("Skipping payment step — invoice creation returned null for orderId={}", order.getId());
        }
    }
}
