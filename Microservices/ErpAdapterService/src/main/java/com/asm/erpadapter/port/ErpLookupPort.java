package com.asm.erpadapter.port;

import com.asm.erpadapter.dto.ErpClientDTO;
import com.asm.erpadapter.dto.ErpPendingOrderPreviewDTO;
import com.asm.erpadapter.dto.ErpPendingOrderSummaryDTO;
import com.asm.erpadapter.dto.ErpProductDTO;
import java.util.List;

public interface ErpLookupPort {
    List<ErpClientDTO> searchClients(String search, int limit);
    List<ErpProductDTO> searchProducts(String search, int limit);
    List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit);
    ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId);
}
