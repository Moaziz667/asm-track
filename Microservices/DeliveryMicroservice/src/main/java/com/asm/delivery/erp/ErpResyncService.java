package com.asm.delivery.erp;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OutboxEvent;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.repository.OutboxRepository;
import com.asm.delivery.service.OutboxProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator-triggered recovery for orders stuck in {@code SYNC_FAILED} — the terminal state reached
 * after the OutboxProcessor exhausts its automatic retries, or after Odoo rejects a delivered command.
 * The automatic retry loop only handles <em>transient</em> outages; this is the escalation tier for
 * permanent failures and business errors a human had to fix in Odoo first.
 *
 * <p>Strategy, in order of preference:
 * <ol>
 *   <li><b>Re-drive the original FAILED outbox event</b> when one exists — preserves the exact payload
 *       (partial items, POD photos, return lines) so the replay is faithful.</li>
 *   <li><b>Re-enqueue a fresh outbox event</b> for the adapter-rejection case (publish succeeded, Odoo
 *       said no, so no failed outbox event exists), reconstructing the payload from the {@link Delivery}.
 *       Ops we cannot fully reconstruct (partial/POD/return) are reported as needing manual review.</li>
 * </ol>
 * Both paths go through the transactional outbox, so resync inherits the same exactly-once delivery and
 * automatic-retry guarantees as the original sync. Downstream is idempotent on {@code txId}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpResyncService {

    private final OrderRepository orderRepo;
    private final DeliveryRepository deliveryRepo;
    private final OutboxRepository outboxRepo;
    private final OutboxProcessor outboxProcessor;
    private final ObjectMapper objectMapper;

    public record ResyncResult(UUID orderId, String blNumber, String status, boolean queued, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", orderId);
            m.put("blNumber", blNumber);
            m.put("status", status);
            m.put("queued", queued);
            if (reason != null) m.put("reason", reason);
            return m;
        }
    }

    /** Resync a single failed order. Safe to call repeatedly (idempotent downstream). */
    @Transactional
    public ResyncResult resync(UUID orderId) {
        Order order = orderRepo.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return new ResyncResult(orderId, null, null, false, "Order not found");
        }
        String blNumber = order.getBlNumber();

        if (!"SYNC_FAILED".equals(order.getOdooSyncStatus())) {
            return new ResyncResult(orderId, blNumber, order.getOdooSyncStatus(), false,
                    "Order is not in SYNC_FAILED state (current: " + order.getOdooSyncStatus() + ")");
        }

        Delivery delivery = deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(null);
        if (delivery == null) {
            return new ResyncResult(orderId, blNumber, order.getOdooSyncStatus(), false, "No shipment found for this order");
        }
        if (resolveErpRef(order) == null) {
            return new ResyncResult(orderId, blNumber, order.getOdooSyncStatus(), false,
                    "Order has no ERP reference — cannot sync");
        }

        // 1. Preferred: re-drive the original FAILED outbox event (exact payload preserved).
        OutboxEvent failed = findFailedErpEventFor(orderId, delivery.getId());
        if (failed != null) {
            markPending(order);
            failed.setStatus("PENDING");
            failed.setRetryCount(0);
            failed.setNextRetryAt(LocalDateTime.now());
            outboxRepo.save(failed);
            log.info("ERP resync — re-queued outbox event eventId={} orderId={} type={}",
                    failed.getId(), orderId, failed.getEventType());
            return new ResyncResult(orderId, blNumber, "PENDING_SYNC", true, "Re-queued original outbox event");
        }

        // 2. Fallback: Odoo rejected a delivered command (no failed event) → enqueue a fresh outbox event,
        //    reconstructing the payload from the delivery. Routes through the same exactly-once outbox path.
        String op = order.getLastSyncOp();
        String type;
        Map<String, Object> payload = new HashMap<>();
        switch (op == null ? "" : op) {
            case "STOCK_FULL" -> {
                type = "ERP_SYNC_STOCK";
                payload.put("deliveryId", delivery.getId().toString());
                payload.put("isPartial", false);
            }
            case "CANCELLATION" -> {
                type = "ERP_SYNC_CANCELLATION";
                payload.put("orderId", orderId.toString());
            }
            case "FAILURE" -> {
                type = "ERP_SYNC_FAILURE";
                payload.put("deliveryId", delivery.getId().toString());
                payload.put("failureCode", delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null);
                payload.put("comment", delivery.getFailReason());
            }
            default -> {
                return new ResyncResult(orderId, blNumber, order.getOdooSyncStatus(), false,
                        "Cannot auto-resync operation '" + op + "' — needs manual review in Odoo");
            }
        }
        markPending(order);
        outboxProcessor.enqueue(type, payload);
        log.info("ERP resync — enqueued fresh outbox event type={} orderId={}", type, orderId);
        return new ResyncResult(orderId, blNumber, "PENDING_SYNC", true, "Re-queued via outbox (" + op + ")");
    }

    /** Resync up to {@code max} of the oldest failed orders. Returns per-order outcomes + a summary. */
    @Transactional
    public Map<String, Object> resyncAll(int max) {
        int cap = Math.min(Math.max(max, 1), 50);
        List<Order> failed = orderRepo.findTop50ByOdooSyncStatusOrderByUpdatedAtAsc("SYNC_FAILED");
        int queued = 0;
        var results = new java.util.ArrayList<Map<String, Object>>();
        for (Order o : failed) {
            if (results.size() >= cap) break;
            ResyncResult r = resync(o.getId());
            if (r.queued()) queued++;
            results.add(r.toMap());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", results.size());
        out.put("queued", queued);
        out.put("results", results);
        return out;
    }

    private void markPending(Order order) {
        order.setOdooSyncStatus("PENDING_SYNC");
        order.setSyncRetryCount(0);
        order.setNextSyncRetryAt(null);
        orderRepo.save(order);
    }

    private String resolveErpRef(Order order) {
        if (order.getErpExternalRef() != null) return order.getErpExternalRef();
        return order.getErpOrderId();
    }

    /** Latest FAILED ERP outbox event whose payload references this order or delivery. */
    private OutboxEvent findFailedErpEventFor(UUID orderId, UUID deliveryId) {
        OutboxEvent match = null; // findByStatus is createdAt-ASC, so the last match is the newest
        for (OutboxEvent e : outboxRepo.findByStatusOrderByCreatedAtAsc("FAILED")) {
            if (e.getEventType() == null || !e.getEventType().startsWith("ERP_SYNC")) continue;
            if (payloadReferences(e, orderId, deliveryId)) match = e;
        }
        return match;
    }

    private boolean payloadReferences(OutboxEvent e, UUID orderId, UUID deliveryId) {
        try {
            Map<String, Object> p = objectMapper.readValue(e.getPayload(), new TypeReference<>() {});
            Object oid = p.get("orderId");
            Object did = p.get("deliveryId");
            return (oid != null && orderId.toString().equals(String.valueOf(oid)))
                    || (did != null && deliveryId != null && deliveryId.toString().equals(String.valueOf(did)));
        } catch (Exception ex) {
            return false;
        }
    }
}
