package com.asm.delivery.erp;

import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.messaging.ErpSyncCommandPublisher;
import com.asm.delivery.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Maps a delivery (shipment) outcome to an ERP-sync command and hands it to the broker. Each
 * delivery is one Odoo picking, so the picking identity (BL number + backorder picking id) is read
 * from the {@link Delivery}, falling back to the order for legacy rows. The actual Odoo work happens
 * asynchronously in ErpAdapter; the sync status is set later by {@code ErpSyncResultConsumer}.
 *
 * <p>Invoked from the transactional Outbox, which guarantees the publish happens exactly once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpSyncService {

    private final ErpSyncCommandPublisher commandPublisher;
    private final OrderRepository orderRepo;

    public void syncOrderCancellation(Delivery delivery, String transactionId) {
        Order order = delivery.getOrder();
        if (order.getErpOrderId() == null) return;
        commandPublisher.publishCancellation(
                delivery.getId().toString(), order.getId().toString(),
                order.getErpOrderId(), pickingRef(delivery), transactionId);
    }

    public void syncStockUpdate(Delivery delivery, String transactionId) {
        Order order = delivery.getOrder();
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishStockFull(
                delivery.getId().toString(), order.getId().toString(), erpOrderId,
                backorderPickingId(delivery), pickingRef(delivery), transactionId);
    }

    public void syncPartialStockUpdate(Delivery delivery, List<PartialDeliveryItem> partialItems, String transactionId) {
        Order order = delivery.getOrder();
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishStockPartial(
                delivery.getId().toString(), order.getId().toString(), erpOrderId,
                partialItems, pickingRef(delivery), transactionId);
    }

    public void syncFailure(Delivery delivery, String failureCode, String comment, String transactionId) {
        Order order = delivery.getOrder();
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishFailure(
                delivery.getId().toString(), order.getId().toString(), erpOrderId,
                failureCode, comment, pickingRef(delivery), transactionId);
    }

    public void syncProofOfDelivery(Delivery delivery, java.util.Map<String, Object> pod, String transactionId) {
        Order order = delivery.getOrder();
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishPod(
                delivery.getId().toString(), order.getId().toString(), erpOrderId,
                pickingRef(delivery), transactionId, pod);
    }

    public void syncReturn(Delivery delivery, List<java.util.Map<String, Object>> items, String reason, String rmaId, String transactionId) {
        Order order = delivery.getOrder();
        String erpOrderId = resolveErpOrderId(order);
        if (erpOrderId == null) return;
        commandPublisher.publishReturn(
                delivery.getId().toString(), order.getId().toString(), erpOrderId,
                pickingRef(delivery), transactionId, reason, rmaId, items);
    }

    /** Picking (BL) number for this shipment — from the delivery, falling back to the order (legacy). */
    private String pickingRef(Delivery delivery) {
        if (delivery.getBlNumber() != null) return delivery.getBlNumber();
        return delivery.getOrder() != null ? delivery.getOrder().getBlNumber() : null;
    }

    private Integer backorderPickingId(Delivery delivery) {
        if (delivery.getOdooBackorderId() != null) return delivery.getOdooBackorderId();
        return delivery.getOrder() != null ? delivery.getOrder().getOdooBackorderId() : null;
    }

    /**
     * Resolves the ERP sale-order reference. erpExternalRef holds the sale-order reference
     * (e.g. S00110) the adapter needs; in the shipment model the backorder is a sibling delivery on
     * the SAME order, so the parent walk is only kept for legacy cloned-order rows.
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
