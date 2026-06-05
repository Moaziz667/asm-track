package com.asm.delivery.erp;

import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.entity.Order;
import com.asm.delivery.messaging.ErpSyncCommandPublisher;
import com.asm.delivery.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Maps a delivery outcome to an ERP-sync command and hands it to the broker. The actual Odoo work
 * happens asynchronously in ErpAdapter; the order's sync status (SYNCED / SYNC_FAILED, backorder id)
 * is set later by {@code ErpSyncResultConsumer} when the result event arrives — not here.
 *
 * <p>Invoked from the transactional Outbox, which guarantees the publish happens exactly once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpSyncService {

    private final ErpSyncCommandPublisher commandPublisher;
    private final OrderRepository orderRepo;

    public void syncOrderCancellation(Order order, String transactionId) {
        if (order.getErpOrderId() == null) return;
        commandPublisher.publishCancellation(
                order.getId().toString(), order.getErpOrderId(), order.getBlNumber(), transactionId);
    }

    public void syncStockUpdate(Order order, String transactionId) {
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishStockFull(
                order.getId().toString(), erpOrderId, order.getOdooBackorderId(), order.getBlNumber(), transactionId);
    }

    public void syncPartialStockUpdate(Order order, List<PartialDeliveryItem> partialItems, String transactionId) {
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishStockPartial(
                order.getId().toString(), erpOrderId, partialItems, order.getBlNumber(), transactionId);
    }

    public void syncFailure(Order order, String failureCode, String comment, String transactionId) {
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishFailure(
                order.getId().toString(), erpOrderId, failureCode, comment, order.getBlNumber(), transactionId);
    }

    /**
     * Resolves the ERP order reference for sync. erpExternalRef holds the sale-order reference
     * (e.g. S00110) the adapter needs for cancel/update; backorder orders have erpOrderId=null
     * (DB unique constraint), so we walk up to the parent order.
     */
    private String resolveErpOrderId(Order order) {
        if (order.getErpExternalRef() != null) return order.getErpExternalRef();
        if (order.getErpOrderId() != null) return order.getErpOrderId();
        if (order.getParentOrderId() != null) {
            return orderRepo.findById(order.getParentOrderId())
                    .map(o -> o.getErpExternalRef() != null ? o.getErpExternalRef() : o.getErpOrderId())
                    .orElse(null);
        }
        return null;
    }
}
