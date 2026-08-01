package com.asm.delivery.erp.client;

import com.asm.delivery.erp.ErpClientDTO;
import com.asm.delivery.erp.ErpCompanyDTO;
import com.asm.delivery.erp.ErpPendingOrderPreviewDTO;
import com.asm.delivery.erp.ErpPendingOrderSummaryDTO;
import com.asm.delivery.erp.ErpProductDTO;
import com.asm.delivery.erp.ErpWarehouseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Declarative client for ErpAdapterService. Service auth + caller-identity headers (incl. the
 * {@code X-Company-Id} the adapter resolves the provider from) are applied globally by
 * {@code ServiceClientConfig}'s Feign interceptor. The ERP provider is <b>not</b> a parameter here:
 * the adapter picks it per-tenant from that tenant's settings, so a caller can never route to another
 * tenant's ERP. The defensive fallback behaviour lives in {@code com.asm.delivery.erp.port.OdooErpAdapter}.
 */
@FeignClient(name = "erp-adapter", url = "${erp.adapter-url:http://erp-adapter:8088}")
public interface ErpAdapterFeignClient {

    // ── Sync operations ──────────────────────────────────────────────────────
    @PostMapping("/api/erp/sync/order-cancellation")
    Map<String, Object> syncOrderCancellation(@RequestParam("erpOrderId") String erpOrderId,
                                              @RequestParam("transactionId") String transactionId,
                                              @RequestParam(value = "pickingRef", required = false) String pickingRef);

    @PostMapping("/api/erp/sync/invoice")
    Map<String, Object> createInvoice(@RequestParam("erpOrderId") String erpOrderId,
                                      @RequestParam(value = "pickingRef", required = false) String pickingRef);

    @GetMapping("/api/erp/sync/invoice-pdf")
    byte[] getInvoicePdf(@RequestParam("invoiceRef") String invoiceRef);

    @GetMapping("/api/erp/sync/delivery-note-pdf")
    byte[] getDeliveryNotePdf(@RequestParam("pickingRef") String pickingRef);

    @PostMapping("/api/erp/sync/full-delivery")
    Map<String, Object> syncFullDelivery(@RequestBody Map<String, Object> body);

    @PostMapping("/api/erp/sync/partial-delivery")
    Map<String, Object> syncPartialDelivery(@RequestBody Map<String, Object> body);

    @PostMapping("/api/erp/sync/failure")
    Map<String, Object> syncFailure(@RequestBody Map<String, Object> body);

    // ── Lookup operations (JSON deserialized straight into the canonical DTOs) ─
    @GetMapping("/api/erp/lookup/clients")
    List<ErpClientDTO> searchClients(@RequestParam("search") String search,
                                     @RequestParam("limit") int limit);

    @GetMapping("/api/erp/lookup/products")
    List<ErpProductDTO> searchProducts(@RequestParam("search") String search,
                                       @RequestParam("limit") int limit);

    @GetMapping("/api/erp/lookup/pending-orders")
    List<ErpPendingOrderSummaryDTO> getPendingOrders(@RequestParam("limit") int limit);

    @GetMapping("/api/erp/lookup/pending-orders/preview")
    ErpPendingOrderPreviewDTO getPendingOrderPreview(@RequestParam("erpOrderId") String erpOrderId);

    @GetMapping("/api/erp/lookup/warehouses")
    List<ErpWarehouseDTO> getWarehouses();

    @GetMapping("/api/erp/lookup/picking-ref")
    Map<String, Object> getPickingRef(@RequestParam("pickingId") String pickingId);

    @GetMapping("/api/erp/lookup/company")
    ErpCompanyDTO getCompany();
}
