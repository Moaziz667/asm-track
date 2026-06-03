package com.asm.erpadapter.adapter.dux;

import com.asm.erpadapter.dto.*;
import com.asm.erpadapter.port.ErpLookupPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DUX ERP lookup adapter — stub pending DUX API documentation.
 */
@Component
@Slf4j
public class DuxLookupAdapter implements ErpLookupPort {

    @Override
    public List<ErpClientDTO> searchClients(String search, int limit) {
        log.warn("DUX searchClients not yet implemented");
        return List.of();
    }

    @Override
    public List<ErpProductDTO> searchProducts(String search, int limit) {
        log.warn("DUX searchProducts not yet implemented");
        return List.of();
    }

    @Override
    public List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit) {
        log.warn("DUX getPendingOrders not yet implemented");
        return List.of();
    }

    @Override
    public ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId) {
        log.warn("DUX getPendingOrderPreview not yet implemented for order {}", erpOrderId);
        return null;
    }

    @Override
    public byte[] getDeliveryNotePdf(String blNumber) {
        log.warn("DUX getDeliveryNotePdf not yet implemented");
        return null;
    }

    @Override
    public List<ErpWarehouseDTO> getWarehouses() {
        log.warn("DUX getWarehouses not yet implemented");
        return List.of();
    }

    @Override
    public String getPickingRef(String pickingId) {
        log.warn("DUX getPickingRef not yet implemented");
        return null;
    }
}
