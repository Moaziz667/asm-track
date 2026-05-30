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

        boolean success = erpAdapterClient.syncOrderCancellation(order.getErpOrderId(), transactionId, null);
        if (success) {
            markSynced(order);
        } else {
            throw new RuntimeException("ERP Cancellation failed — erpOrderId=" + order.getErpOrderId());
        }
    }

    public void syncStockUpdate(Order order, String transactionId) {
        // Backorder orders have erpOrderId=null (unique constraint) — resolve via parent order.
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;

        boolean success = erpAdapterClient.syncFullDelivery(
                erpOrderId, order.getOdooBackorderId(), transactionId, null);
        if (success) {
            markSynced(order);
        } else {
            throw new RuntimeException("ERP Stock update failed — erpOrderId=" + erpOrderId);
        }
    }

    public void syncPartialStockUpdate(Order order, List<PartialDeliveryItem> partialItems, String transactionId) {
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;

        Map<String, Object> result = erpAdapterClient.syncPartialDelivery(
                erpOrderId, partialItems, transactionId, null);
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
            throw new RuntimeException("ERP Partial sync failed — erpOrderId=" + erpOrderId);
        }
    }

    public void syncFailure(Order order, String failureCode, String comment, String transactionId) {
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;

        boolean success = erpAdapterClient.syncFailure(erpOrderId, failureCode, comment, transactionId, null);
        if (success) {
            markSynced(order);
        } else {
            throw new RuntimeException("ERP Failure sync failed — erpOrderId=" + erpOrderId);
        }
    }

    /**
     * Resolves the ERP order ID for sync purposes.
     * Backorder orders have erpOrderId=null (DB unique constraint) so we walk up
     * to the parent order to get the original Odoo sale order reference.
     */
    private String resolveErpOrderId(Order order) {
        if (order.getErpOrderId() != null) return order.getErpOrderId();
        if (order.getParentOrderId() != null) {
            return orderRepo.findById(order.getParentOrderId())
                    .map(Order::getErpOrderId)
                    .orElse(null);
        }
        return null;
    }

    private void markSynced(Order order) {
        order.setOdooSyncStatus("SYNCED");
        order.setSyncRetryCount(0);
        order.setNextSyncRetryAt(null);
        orderRepo.save(order);
    }
}
