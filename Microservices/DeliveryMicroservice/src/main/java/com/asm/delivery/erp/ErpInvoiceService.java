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
            Map<String, Object> resp = feign.createInvoice(provider, erpOrderId, pickingRef);
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
}
