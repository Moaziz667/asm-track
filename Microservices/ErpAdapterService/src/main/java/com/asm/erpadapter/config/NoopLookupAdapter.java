package com.asm.erpadapter.config;

import com.asm.erpadapter.dto.*;
import com.asm.erpadapter.port.ErpLookupPort;

import java.util.List;

/** No-op lookup adapter for companies with erp_type = NONE. */
class NoopLookupAdapter implements ErpLookupPort {
    @Override public List<ErpClientDTO> searchClients(String s, int l) { return List.of(); }
    @Override public List<ErpProductDTO> searchProducts(String s, int l) { return List.of(); }
    @Override public List<ErpPendingOrderSummaryDTO> getPendingOrders(int l) { return List.of(); }
    @Override public ErpPendingOrderPreviewDTO getPendingOrderPreview(String id) { return null; }
    @Override public byte[] getDeliveryNotePdf(String blNumber) { return null; }
    @Override public List<ErpWarehouseDTO> getWarehouses() { return List.of(); }
}
