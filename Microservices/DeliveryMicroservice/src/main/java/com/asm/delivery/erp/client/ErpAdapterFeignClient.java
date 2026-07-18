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
 * Declarative client for ErpAdapterService. Service auth + caller-identity headers are applied
 * globally by {@code ServiceClientConfig}'s Feign interceptor. The defensive fallback behaviour
 * (return false/null/empty on failure) and provider selection live in the
 * {@code com.asm.delivery.erp.port.OdooErpAdapter} implementation of {@code ErpPort}, not here.
 */
@FeignClient(name = "erp-adapter", url = "${erp.adapter-url:http://erp-adapter:8088}")
public interface ErpAdapterFeignClient {

    // ── Sync operations ──────────────────────────────────────────────────────
    @PostMapping("/api/erp/sync/order-cancellation")
    Map<String, Object> syncOrderCancellation(@RequestParam("erpProvider") String erpProvider,
                                              @RequestParam("erpOrderId") String erpOrderId,
                                              @RequestParam("transactionId") String transactionId,
                                              @RequestParam(value = "pickingRef", required = false) String pickingRef);

    @PostMapping("/api/erp/sync/invoice")
    Map<String, Object> createInvoice(@RequestParam("erpProvider") String erpProvider,
                                      @RequestParam("erpOrderId") String erpOrderId,
                                      @RequestParam(value = "pickingRef", required = false) String pickingRef);

    @GetMapping("/api/erp/sync/invoice-pdf")
    byte[] getInvoicePdf(@RequestParam("erpProvider") String erpProvider,
                         @RequestParam("invoiceRef") String invoiceRef);

    @PostMapping("/api/erp/sync/full-delivery")
    Map<String, Object> syncFullDelivery(@RequestParam("erpProvider") String erpProvider,
                                         @RequestBody Map<String, Object> body);

    @PostMapping("/api/erp/sync/partial-delivery")
    Map<String, Object> syncPartialDelivery(@RequestParam("erpProvider") String erpProvider,
                                            @RequestBody Map<String, Object> body);

    @PostMapping("/api/erp/sync/failure")
    Map<String, Object> syncFailure(@RequestParam("erpProvider") String erpProvider,
                                    @RequestBody Map<String, Object> body);

    // ── Lookup operations (JSON deserialized straight into the canonical DTOs) ─
    @GetMapping("/api/erp/lookup/clients")
    List<ErpClientDTO> searchClients(@RequestParam("erpProvider") String erpProvider,
                                     @RequestParam("search") String search,
                                     @RequestParam("limit") int limit);

    @GetMapping("/api/erp/lookup/products")
    List<ErpProductDTO> searchProducts(@RequestParam("erpProvider") String erpProvider,
                                       @RequestParam("search") String search,
                                       @RequestParam("limit") int limit);

    @GetMapping("/api/erp/lookup/pending-orders")
    List<ErpPendingOrderSummaryDTO> getPendingOrders(@RequestParam("erpProvider") String erpProvider,
                                                     @RequestParam("limit") int limit);

    @GetMapping("/api/erp/lookup/pending-orders/preview")
    ErpPendingOrderPreviewDTO getPendingOrderPreview(@RequestParam("erpProvider") String erpProvider,
                                                     @RequestParam("erpOrderId") String erpOrderId);

    @GetMapping("/api/erp/lookup/warehouses")
    List<ErpWarehouseDTO> getWarehouses(@RequestParam("erpProvider") String erpProvider);

    @GetMapping("/api/erp/lookup/picking-ref")
    Map<String, Object> getPickingRef(@RequestParam("erpProvider") String erpProvider,
                                      @RequestParam("pickingId") String pickingId);

    @GetMapping("/api/erp/lookup/company")
    ErpCompanyDTO getCompany(@RequestParam("erpProvider") String erpProvider);
}
