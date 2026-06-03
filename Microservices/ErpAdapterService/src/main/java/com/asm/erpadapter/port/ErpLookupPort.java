package com.asm.erpadapter.port;

import com.asm.erpadapter.dto.ErpClientDTO;
import com.asm.erpadapter.dto.ErpPendingOrderPreviewDTO;
import com.asm.erpadapter.dto.ErpPendingOrderSummaryDTO;
import com.asm.erpadapter.dto.ErpProductDTO;
import com.asm.erpadapter.dto.ErpWarehouseDTO;
import java.util.List;

public interface ErpLookupPort {
    List<ErpClientDTO> searchClients(String search, int limit);
    List<ErpProductDTO> searchProducts(String search, int limit);
    List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit);
    ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId);
    byte[] getDeliveryNotePdf(String blNumber);

    /** Source depots: the ERP's warehouses (Odoo {@code stock.warehouse}) with address/coordinates. */
    List<ErpWarehouseDTO> getWarehouses();

    /** Resolve a picking's reference/name (e.g. "WH/OUT/00007") from its ERP id — used to link backorders. */
    String getPickingRef(String pickingId);
}
