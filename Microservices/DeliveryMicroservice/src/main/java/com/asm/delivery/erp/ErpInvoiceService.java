package com.asm.delivery.erp;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.erp.client.ErpAdapterFeignClient;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Admin-triggered, <b>synchronous</b> ERP invoicing: the operator clicks "create invoice" on a
 * delivered shipment and gets the ERP invoice reference back immediately. ASM only <b>triggers</b> —
 * the ERP prices/taxes/accounts the invoice from its own config (ERPNext {@code make_sales_invoice} +
 * submit, Odoo delivered-qty wizard + post). Best-effort: on any failure the operator is told to
 * invoice manually in the ERP — a delivery is never blocked or corrupted by a billing hiccup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpInvoiceService {

    private final ErpAdapterFeignClient feign;
    private final DeliveryRepository deliveryRepository;

    @Transactional(readOnly = true)
    public String createInvoice(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> AppException.badRequest("Delivery not found"));
        if (!isDelivered(delivery.getStatus())) {
            throw AppException.badRequest("Only a delivered order can be invoiced.");
        }
        Order order = delivery.getOrder();
        String provider = order.getSource() != null ? order.getSource().name().toLowerCase() : "odoo";
        // The ERP invoicing target is the sale-order reference; the BL/picking scopes the delivered qty.
        String erpOrderId = order.getErpExternalRef() != null ? order.getErpExternalRef() : order.getErpOrderId();
        String pickingRef = delivery.getBlNumber() != null ? delivery.getBlNumber() : order.getBlNumber();
        if (erpOrderId == null) {
            throw AppException.badRequest("This delivery has no ERP order reference to invoice.");
        }
        try {
            Map<String, Object> resp = feign.createInvoice(erpOrderId, pickingRef);
            Object ref = resp != null ? resp.get("invoiceRef") : null;
            if (ref != null) {
                log.info("Invoice created delivery={} provider={} erpOrderId={} ref={}", deliveryId, provider, erpOrderId, ref);
                return String.valueOf(ref);
            }
        } catch (Exception e) {
            log.warn("createInvoice delivery={} provider={} failed: {}", deliveryId, provider, e.getMessage());
        }
        throw AppException.badRequest("Invoice could not be created — please create it manually in the ERP.");
    }

    private static boolean isDelivered(DeliveryStatus s) {
        return s == DeliveryStatus.DELIVERED || s == DeliveryStatus.PARTIALLY_DELIVERED;
    }

    /**
     * The delivery note (bon de livraison) <b>as the ERP renders it</b>.
     *
     * <p>ASM used to draw this document itself. That was the only fiscal document it produced, and it
     * reproduced none of what makes one valid: the issuer was picked with an unordered
     * {@code findFirst()}, the issue date was {@code LocalDate.now()} (so a reprint changed the date
     * under a fixed number), the font encoding was WinAnsi (so Arabic names silently vanished), and
     * lot/serial traceability had no field to live in. All of that is correct in the ERP's own report.
     *
     * <p>There is deliberately <b>no fallback to a locally drawn document</b>. Serving one when the ERP
     * copy cannot be fetched would hand the driver a non-compliant sheet that looks exactly like the
     * compliant one — the precise failure this change exists to remove. A clear error is safer.
     */
    @Transactional(readOnly = true)
    public byte[] getDeliveryNotePdf(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + deliveryId));
        Order order = delivery.getOrder();
        String pickingRef = delivery.getBlNumber() != null ? delivery.getBlNumber()
                : (order != null ? order.getBlNumber() : null);
        if (pickingRef == null || pickingRef.isBlank()) {
            throw AppException.badRequest(
                    "Cette livraison n'a pas de référence de bon de livraison dans l'ERP.");
        }
        try {
            byte[] pdf = feign.getDeliveryNotePdf(pickingRef);
            if (pdf != null && pdf.length > 0) return pdf;
        } catch (Exception e) {
            log.warn("getDeliveryNotePdf delivery={} pickingRef={} failed: {}", deliveryId, pickingRef, e.getMessage());
        }
        throw AppException.badRequest(
                "Bon de livraison indisponible — ouvrez-le dans l'ERP (réf. " + pickingRef + ").");
    }

    /** Fetch the rendered PDF of an ERP invoice for this delivery's provider. Null/empty on failure. */
    @Transactional(readOnly = true)
    public byte[] getInvoicePdf(UUID deliveryId, String invoiceRef) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> AppException.badRequest("Delivery not found"));
        if (invoiceRef == null || invoiceRef.isBlank()) {
            throw AppException.badRequest("Missing invoice reference.");
        }
        try {
            byte[] pdf = feign.getInvoicePdf(invoiceRef);
            if (pdf != null && pdf.length > 0) return pdf;
        } catch (Exception e) {
            log.warn("getInvoicePdf delivery={} ref={} failed: {}", deliveryId, invoiceRef, e.getMessage());
        }
        throw AppException.badRequest("Invoice PDF unavailable — open it in the ERP.");
    }
}
