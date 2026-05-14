package com.asm.delivery.erp;

import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.entity.Order;
import com.asm.delivery.erp.client.ErpAdapterClient;
import com.asm.delivery.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Clean stateless bridge for ERP synchronization.
 * Retries and state are managed by the Transactional Outbox.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpSyncService {

    private final ErpAdapterClient erpAdapterClient;
    private final OrderRepository  orderRepo;

    public void syncOrderCancellation(Order order, String transactionId) {
        if (order.getErpOrderId() == null) return;
        String companyId = order.getCompanyId() != null ? order.getCompanyId().toString() : null;

        boolean success = erpAdapterClient.syncOrderCancellation(order.getErpOrderId(), transactionId, null, companyId);
        if (success) {
            markSynced(order);
        } else {
            throw new RuntimeException("ERP Cancellation failed for " + order.getErpOrderId());
        }
    }

    public void syncStockUpdate(Order order, String transactionId) {
        if (order.getErpOrderId() == null) return;
        String companyId = order.getCompanyId() != null ? order.getCompanyId().toString() : null;

        boolean success = erpAdapterClient.syncFullDelivery(
                order.getErpOrderId(), order.getOdooBackorderId(), transactionId, null, companyId);
        if (success) {
            markSynced(order);
        } else {
            throw new RuntimeException("ERP Stock update failed for " + order.getErpOrderId());
        }
    }

    public void syncPartialStockUpdate(Order order, List<PartialDeliveryItem> partialItems, String transactionId) {
        if (order.getErpOrderId() == null) return;
        String companyId = order.getCompanyId() != null ? order.getCompanyId().toString() : null;

        Map<String, Object> result = erpAdapterClient.syncPartialDelivery(
                order.getErpOrderId(), partialItems, transactionId, null, companyId);
        boolean success = Boolean.TRUE.equals(result.get("success"));

        if (success) {
            Object backorderId = result.get("backorderPickingId");
            if (backorderId instanceof Integer bi) {
                order.setOdooBackorderId(bi);
            } else if (backorderId instanceof String bs) {
                order.setOdooBackorderId(Integer.parseInt(bs));
            }
            markSynced(order);
        } else {
            throw new RuntimeException("ERP Partial sync failed for " + order.getErpOrderId());
        }
    }

    public void syncFailure(Order order, String failureCode, String comment, String transactionId) {
        if (order.getErpOrderId() == null) return;
        String companyId = order.getCompanyId() != null ? order.getCompanyId().toString() : null;

        boolean success = erpAdapterClient.syncFailure(order.getErpOrderId(), failureCode, comment, transactionId, null, companyId);
        if (success) {
            markSynced(order);
        } else {
            throw new RuntimeException("ERP Failure sync failed for " + order.getErpOrderId());
        }
    }

    private void markSynced(Order order) {
        order.setOdooSyncStatus("SYNCED");
        order.setSyncRetryCount(0);
        order.setNextSyncRetryAt(null);
        orderRepo.save(order);
    }
}
