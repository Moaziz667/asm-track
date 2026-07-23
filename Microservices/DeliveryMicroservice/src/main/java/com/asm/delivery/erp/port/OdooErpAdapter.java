package com.asm.delivery.erp.port;

import com.asm.delivery.erp.ErpClientDTO;
import com.asm.delivery.erp.ErpCompanyDTO;
import com.asm.delivery.erp.ErpPendingOrderPreviewDTO;
import com.asm.delivery.erp.ErpPendingOrderSummaryDTO;
import com.asm.delivery.erp.ErpProductDTO;
import com.asm.delivery.erp.ErpWarehouseDTO;
import com.asm.delivery.erp.client.ErpAdapterFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Tenant-aware ERP lookup adapter that delegates to the ErpAdapter microservice.
 *
 * <p>The provider (Odoo, ERPNext, …) is chosen by the ErpAdapter service itself, per tenant, from that
 * tenant's settings — the propagated {@code X-Company-Id} is enough. This adapter therefore does not
 * pass a provider key; it's a thin, typed passthrough over the Feign client.
 *
 * <p>Defensive "never throw — return a safe default" behavior: transport/ERP errors are caught here and
 * surfaced as empty list / null, never propagated to callers.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OdooErpAdapter implements ErpPort {

    private final ErpAdapterFeignClient feign;

    @Override
    public List<ErpClientDTO> searchClients(String search, int limit) {
        try {
            List<ErpClientDTO> r = feign.searchClients(search != null ? search : "", limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP searchClients failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<ErpProductDTO> searchProducts(String search, int limit) {
        try {
            List<ErpProductDTO> r = feign.searchProducts(search != null ? search : "", limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP searchProducts failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) {
        try {
            List<ErpPendingOrderSummaryDTO> r = feign.getPendingOrders(limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP getPendingOrders failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId) {
        try {
            return feign.getPendingOrderPreview(erpOrderId);
        } catch (Exception e) {
            log.error("ERP getPendingOrderPreview failed for erpOrderId={}", erpOrderId, e);
            return null;
        }
    }

    @Override
    public List<ErpWarehouseDTO> getWarehouses() {
        try {
            List<ErpWarehouseDTO> r = feign.getWarehouses();
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP getWarehouses failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public String getPickingRef(String pickingId) {
        try {
            Map<String, Object> body = feign.getPickingRef(pickingId);
            return body != null && body.get("ref") != null ? String.valueOf(body.get("ref")) : null;
        } catch (Exception e) {
            log.warn("ERP getPickingRef failed for pickingId={}: {}", pickingId, e.getMessage());
            return null;
        }
    }

    @Override
    public ErpCompanyDTO getCompany() {
        try {
            return feign.getCompany();
        } catch (Exception e) {
            log.warn("ERP getCompany failed: {}", e.getMessage());
            return null;
        }
    }

}
