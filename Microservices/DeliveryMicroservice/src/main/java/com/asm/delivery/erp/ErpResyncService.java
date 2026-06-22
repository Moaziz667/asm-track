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
    private final com.asm.delivery.service.AuditLogService auditLogService;

    /** Grace window before the reconciliation sweep re-drives a PENDING_SYNC order (B1). */
    @org.springframework.beans.factory.annotation.Value("${erp.reconcile.stuck-minutes:15}")
    private int stuckMinutes;

    /**
     * Self-reference so the scheduled sweep calls the @Transactional reEnqueueStuckOrder THROUGH the
     * Spring proxy. A plain this.reEnqueueStuckOrder(...) would bypass the proxy, leaving no active
     * transaction for the pessimistic findByIdForUpdate lock.
     */
    private ErpResyncService self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy ErpResyncService self) {
        this.self = self;
    }

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
            auditLogService.logAction(null, "ERP_RESYNC", "ORDER", orderId.toString(),
                    Map.of("bl", blNumber != null ? blNumber : "", "via", "outbox-event"));
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
        auditLogService.logAction(null, "ERP_RESYNC", "ORDER", orderId.toString(),
                Map.of("bl", blNumber != null ? blNumber : "", "via", "outbox-" + op));
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

    /**
     * B1 — Reconciliation sweep for orders stuck in {@code PENDING_SYNC}.
     *
     * <p>The outbox guarantees the ERP <em>command</em> is published, and the async
     * {@code erp.sync.result} event is what flips an order to SYNCED / SYNC_FAILED. If that result
     * event is ever lost (consumer crash before commit, broker redelivery exhausted), the order can
     * sit in {@code PENDING_SYNC} forever with no automatic recovery — the outbox event is already
     * PROCESSED and nothing re-drives it.
     *
     * <p>This sweep finds orders that have been PENDING_SYNC longer than the grace window and whose
     * outbox event is no longer pending/processing (i.e. the command was sent but no result came back),
     * and re-enqueues a fresh sync so the result loop runs again. Re-enqueue is safe: downstream is
     * idempotent on {@code txId} and the backorder on {@code odooBackorderId}.
     *
     * <p>Runs every 5 minutes; the grace window ({@code erp.reconcile.stuck-minutes}, default 15)
     * keeps it from racing a sync that is simply still in flight.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${erp.reconcile.fixed-delay-ms:300000}")
    public void reconcileStuckPendingSync() {
        int graceMinutes = stuckMinutes;
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(graceMinutes);
        List<Order> stuck = orderRepo.findTop50ByOdooSyncStatusOrderByUpdatedAtAsc("PENDING_SYNC");
        int reconciled = 0;
        for (Order order : stuck) {
            // Oldest-first: once we hit one inside the grace window, the rest are newer → stop.
            if (order.getUpdatedAt() != null && order.getUpdatedAt().isAfter(cutoff)) break;
            try {
                if (self.reEnqueueStuckOrder(order.getId())) reconciled++;
            } catch (Exception e) {
                log.warn("Reconcile sweep: failed to re-drive stuck orderId={}: {}", order.getId(), e.getMessage());
            }
        }
        if (reconciled > 0) {
            log.warn("Reconcile sweep: re-drove {} order(s) stuck in PENDING_SYNC beyond {} min", reconciled, graceMinutes);
        }
    }

    /**
     * Re-enqueues the sync for an order still stuck in PENDING_SYNC (the result never came back).
     * Reconstructs the command from the last sync op + the delivery, mirroring {@link #resync} but
     * without the SYNC_FAILED precondition. Returns true if an event was queued.
     */
    @Transactional
    public boolean reEnqueueStuckOrder(UUID orderId) {
        Order order = orderRepo.findByIdForUpdate(orderId).orElse(null);
        if (order == null || !"PENDING_SYNC".equals(order.getOdooSyncStatus())) return false;

        Delivery delivery = deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(null);
        if (delivery == null || resolveErpRef(order) == null) return false;

        String op = order.getLastSyncOp();
        String type;
        Map<String, Object> payload = new HashMap<>();
        switch (op == null ? "STOCK_FULL" : op) {
            case "STOCK_PARTIAL", "STOCK_FULL" -> {
                type = "ERP_SYNC_STOCK";
                payload.put("deliveryId", delivery.getId().toString());
                payload.put("isPartial", "STOCK_PARTIAL".equals(op));
            }
            case "CANCELLATION" -> {
                type = "ERP_SYNC_CANCELLATION";
                payload.put("orderId", orderId.toString());
                payload.put("deliveryId", delivery.getId().toString());
            }
            case "FAILURE" -> {
                type = "ERP_SYNC_FAILURE";
                payload.put("deliveryId", delivery.getId().toString());
                payload.put("failureCode", delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null);
                payload.put("comment", delivery.getFailReason());
            }
            default -> {
                log.warn("Reconcile sweep: cannot auto re-drive op '{}' for orderId={} — leaving for operator", op, orderId);
                return false;
            }
        }
        // Touch updatedAt so the sweep doesn't immediately re-pick the same row next tick.
        order.setNextSyncRetryAt(null);
        orderRepo.save(order);
        outboxProcessor.enqueue(type, payload);
        log.info("Reconcile sweep: re-enqueued {} for stuck orderId={}", type, orderId);
        return true;
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
