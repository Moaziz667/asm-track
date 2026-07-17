package com.asm.delivery.erp.port;

import com.asm.delivery.erp.ErpClientDTO;
import com.asm.delivery.erp.ErpCompanyDTO;
import com.asm.delivery.erp.ErpPendingOrderPreviewDTO;
import com.asm.delivery.erp.ErpPendingOrderSummaryDTO;
import com.asm.delivery.erp.ErpProductDTO;
import com.asm.delivery.erp.ErpWarehouseDTO;

import java.util.List;

/**
 * The seam between ASM and whatever ERP an instance is wired to, for the <b>synchronous lookup</b>
 * operations (reference data pulled on demand: clients, products, pending orders, warehouses, the
 * company record, picking refs).
 *
 * <p><b>Canonical contract:</b> every method returns a <b>provider-neutral typed DTO</b>, never a raw
 * {@code Map}. Each adapter is responsible for translating its ERP's shape into these DTOs, so callers
 * never see Odoo/ERPNext/… specifics. A second ERP is one more {@code implements ErpPort} adapter plus
 * a config flip; no caller changes.
 *
 * <p><b>Failure contract:</b> every method is defensive — transport/ERP errors are caught by the adapter
 * and surfaced as a safe default (empty list / {@code null}), never thrown.
 */
public interface ErpPort {

    List<ErpClientDTO> searchClients(String search, int limit);

    List<ErpProductDTO> searchProducts(String search, int limit);

    List<ErpPendingOrderSummaryDTO> getPendingOrders(int limit);

    ErpPendingOrderPreviewDTO getPendingOrderPreview(String erpOrderId);

    List<ErpWarehouseDTO> getWarehouses();

    String getPickingRef(String pickingId);

    ErpCompanyDTO getCompany();
}
