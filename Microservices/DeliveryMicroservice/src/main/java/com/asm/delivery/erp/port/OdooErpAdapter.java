package com.asm.delivery.erp.port;

import com.asm.delivery.erp.client.ErpAdapterFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Odoo implementation of the lookup {@link ErpPort}.
 *
 * <p>Selected when {@code erp.provider} is {@code odoo} (the default). It owns the Odoo-specific
 * details: the provider key sent to the ErpAdapter microservice and the defensive "never throw —
 * return a safe default" behavior the rest of the codebase relies on (previously in the now-deleted
 * {@code ErpAdapterClient}). The HTTP transport, service-auth and caller-identity headers are handled
 * by {@link ErpAdapterFeignClient} and the shared Feign interceptor.
 *
 * <p>To add another ERP: write {@code SapErpAdapter implements ErpPort} annotated with
 * {@code @ConditionalOnProperty(name = "erp.provider", havingValue = "sap")} and set the property.
 */
@Component
@ConditionalOnProperty(name = "erp.provider", havingValue = "odoo", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class OdooErpAdapter implements ErpPort {

    private final ErpAdapterFeignClient feign;

    /** Provider key the ErpAdapter microservice routes on. Constant for this adapter. */
    @Value("${erp.default-provider:odoo}")
    private String provider;

    @Override
    public List<Map<String, Object>> searchClients(String search, int limit) {
        try {
            List<Map<String, Object>> r = feign.searchClients(provider, search != null ? search : "", limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP searchClients failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> searchProducts(String search, int limit) {
        try {
            List<Map<String, Object>> r = feign.searchProducts(provider, search != null ? search : "", limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP searchProducts failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> getPendingOrders(int limit) {
        try {
            List<Map<String, Object>> r = feign.getPendingOrders(provider, limit);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP getPendingOrders failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public Map<String, Object> getPendingOrderPreview(String erpOrderId) {
        try {
            return feign.getPendingOrderPreview(provider, erpOrderId);
        } catch (Exception e) {
            log.error("ERP getPendingOrderPreview failed for erpOrderId={}", erpOrderId, e);
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> getWarehouses() {
        try {
            List<Map<String, Object>> r = feign.getWarehouses(provider);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.error("ERP getWarehouses failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public String getPickingRef(String pickingId) {
        try {
            Map<String, Object> body = feign.getPickingRef(provider, pickingId);
            return body != null && body.get("ref") != null ? String.valueOf(body.get("ref")) : null;
        } catch (Exception e) {
            log.warn("ERP getPickingRef failed for pickingId={}: {}", pickingId, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> getCompany() {
        try {
            return feign.getCompany(provider);
        } catch (Exception e) {
            log.warn("ERP getCompany failed: {}", e.getMessage());
            return null;
        }
    }

}
