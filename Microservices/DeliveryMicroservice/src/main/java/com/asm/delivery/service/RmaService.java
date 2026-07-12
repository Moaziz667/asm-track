package com.asm.delivery.service;

import com.asm.delivery.dto.request.CreateRmaRequest;
import com.asm.delivery.dto.response.RmaResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RmaPhotoRepository;
import com.asm.delivery.repository.RmaRepository;
import com.asm.delivery.repository.RmaStatusHistoryRepository;
import com.asm.delivery.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RMA (returns) lifecycle: REQUESTED → APPROVED → RECEIVED → RESTOCKED, plus REJECTED/CANCELLED.
 * On RESTOCKED, a reverse stock move + note are pushed to the ERP via the transactional outbox.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RmaService {

    private final RmaRepository rmaRepository;
    private final RmaPhotoRepository rmaPhotoRepository;
    private final RmaStatusHistoryRepository rmaStatusHistoryRepository;
    private final DeliveryRepository deliveryRepository;
    private final OutboxProcessor outboxProcessor;
    private final AuditLogService auditLogService;
    private final EventPublisher eventPublisher;

    /** Statuses that count as an "open" return — used to block a duplicate RMA on the same delivery. */
    public static final Set<RmaStatus> OPEN_STATUSES =
            EnumSet.of(RmaStatus.REQUESTED, RmaStatus.APPROVED, RmaStatus.RECEIVED);

    @Transactional
    public RmaResponse create(CreateRmaRequest req, UserPrincipal principal) {
        Delivery delivery = deliveryRepository.findByIdWithOrder(req.getDeliveryId())
                .orElseThrow(() -> AppException.notFound("DELIVERY_NOT_FOUND", "Livraison introuvable."));
        Order order = delivery.getOrder();

        // Only deliveries that actually reached the customer can be returned.
        if (delivery.getStatus() != DeliveryStatus.DELIVERED && delivery.getStatus() != DeliveryStatus.PARTIALLY_DELIVERED) {
            throw AppException.badRequest("DELIVERY_NOT_RETURNABLE",
                    "Un retour ne peut être créé que pour une livraison livrée ou partiellement livrée.");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw AppException.badRequest("RMA_EMPTY", "Au moins un article doit être retourné.");
        }

        // D5 — Idempotency: refuse a second open return for the same delivery (e.g. a double-click).
        // A terminal return (RESTOCKED/REJECTED/CANCELLED) does not block raising a new one.
        boolean alreadyOpen = rmaRepository.findByDeliveryIdOrderByCreatedAtDesc(delivery.getId()).stream()
                .anyMatch(existing -> OPEN_STATUSES.contains(existing.getStatus()));
        if (alreadyOpen) {
            throw AppException.conflict("RMA_ALREADY_OPEN",
                    "Un retour est déjà en cours pour cette livraison.");
        }

        Rma rma = Rma.builder()
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .blNumber(delivery.getBlNumber() != null ? delivery.getBlNumber() : (order != null ? order.getBlNumber() : null))
                .clientName(order != null ? order.getClientName() : null)
                .status(RmaStatus.REQUESTED)
                .reason(req.getReason())
                .createdBy(principal != null ? principal.getDisplayName() : null)
                .build();

        // D3 — A customer can only return what was actually delivered minus what was already returned.
        // Build a per-SKU map of the delivered quantity from the order lines, then subtract quantities
        // from prior RMAs that physically reached the warehouse (RECEIVED/RESTOCKED).
        Map<String, Integer> deliveredBySku = deliveredQuantitiesBySku(order);

        Map<String, Integer> alreadyReturned = new HashMap<>();
        rmaRepository.sumReturnedQtyBySku(delivery.getId()).forEach(row -> {
            String sku = (String) row[0];
            Long qty = (Long) row[1];
            if (sku != null && qty != null) alreadyReturned.merge(sku.trim(), qty.intValue(), Integer::sum);
        });

        for (CreateRmaRequest.Item it : req.getItems()) {
            if (it.getQuantity() == null || it.getQuantity() <= 0) continue;
            String skuKey = it.getSku() != null ? it.getSku().trim() : null;
            int requested = it.getQuantity();
            int delivered = deliveredBySku.getOrDefault(skuKey, requested);
            int returned = alreadyReturned.getOrDefault(skuKey, 0);
            int returnable = Math.max(delivered - returned, 0);
            int qty = Math.min(requested, returnable);
            if (qty <= 0) continue; // nothing returnable for this SKU
            rma.addItem(RmaItem.builder()
                    .sku(it.getSku())
                    .name(it.getName())
                    .quantity(qty)
                    .unitPrice(it.getUnitPrice())
                    .condition(it.getCondition() != null ? it.getCondition() : RmaItemCondition.RESELLABLE)
                    .reason(it.getReason())
                    .build());
        }
        if (rma.getItems().isEmpty()) {
            throw AppException.badRequest("RMA_EMPTY", "Au moins un article avec une quantité valide (et effectivement livrée) est requis.");
        }

        Rma saved = rmaRepository.save(rma);
        recordHistory(saved.getId(), null, RmaStatus.REQUESTED, req.getReason(), principal);
        auditLogService.logAction(principal, "CREATE_RMA", "RMA", saved.getId().toString(),
                Map.of("delivery", String.valueOf(saved.getDeliveryId()), "items", saved.getItems().size()));
        eventPublisher.publishRmaStatusChanged(saved);
        return RmaResponse.from(saved);
    }

    /** Append one immutable timeline row for an RMA transition. Actor stored denormalised (name + role). */
    private void recordHistory(UUID rmaId, RmaStatus from, RmaStatus to, String note, UserPrincipal principal) {
        rmaStatusHistoryRepository.save(RmaStatusHistory.builder()
                .rmaId(rmaId)
                .fromStatus(from)
                .toStatus(to)
                .note(note != null && !note.isBlank() ? note.trim() : null)
                .actedByName(principal != null ? principal.getDisplayName() : null)
                .actedByRole(principal != null ? principal.getRole() : "SYSTEM")
                .build());
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RmaResponse> list(
            RmaStatus status, String q, java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
            org.springframework.data.domain.Pageable pageable) {
        LocalDateTime from = dateFrom != null ? dateFrom.atStartOfDay() : null;
        LocalDateTime to = dateTo != null ? dateTo.atTime(java.time.LocalTime.MAX) : null;
        return rmaRepository.searchPaged(status, q, from, to, pageable).map(RmaResponse::from);
    }

    @Transactional(readOnly = true)
    public RmaResponse get(UUID id) {
        RmaResponse resp = RmaResponse.from(load(id));
        resp.setPhotoUrls(rmaPhotoRepository.findByRmaIdOrderByCreatedAtAsc(id).stream()
                .map(com.asm.delivery.entity.RmaPhoto::getUrl).toList());
        return resp;
    }

    /**
     * Per-SKU quantity still returnable for a delivery: delivered (order lines) minus what already
     * physically returned (RECEIVED/RESTOCKED), clamped ≥ 0. Same formula {@link #create} enforces, so the
     * public return-form preview matches what a create will actually accept.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> returnableQuantitiesBySku(Delivery delivery) {
        Map<String, Integer> delivered = deliveredQuantitiesBySku(delivery.getOrder());
        Map<String, Integer> returned = new HashMap<>();
        rmaRepository.sumReturnedQtyBySku(delivery.getId()).forEach(row -> {
            String sku = (String) row[0];
            Long qty = (Long) row[1];
            if (sku != null && qty != null) returned.merge(sku.trim(), qty.intValue(), Integer::sum);
        });
        Map<String, Integer> out = new LinkedHashMap<>();
        delivered.forEach((sku, dq) -> out.put(sku, Math.max(dq - returned.getOrDefault(sku, 0), 0)));
        return out;
    }

    @Transactional
    public RmaResponse transition(UUID id, RmaStatus target, String note, UserPrincipal principal) {
        Rma rma = load(id);
        RmaStatus fromStatus = rma.getStatus();
        assertTransition(fromStatus, target);

        // D4 — Rejecting or cancelling a return is an audit-sensitive decision: a reason is mandatory
        // so the trail always records WHY a customer return was refused or dropped.
        if ((target == RmaStatus.REJECTED || target == RmaStatus.CANCELLED) && (note == null || note.isBlank())) {
            throw AppException.badRequest("RMA_REASON_REQUIRED",
                    "Un motif est obligatoire pour rejeter ou annuler un retour.");
        }

        rma.setStatus(target);
        if (note != null && !note.isBlank()) rma.setResolutionNote(note.trim());
        if (target == RmaStatus.RECEIVED) rma.setReceivedAt(LocalDateTime.now());
        if (target == RmaStatus.RESTOCKED) {
            rma.setRestockedAt(LocalDateTime.now());
            // D2 — Mark the reverse-move sync as pending BEFORE enqueueing, so the async ERP result
            // can flip it to SYNCED / SYNC_FAILED. Without this the return would look "done" even if
            // Odoo never accepted the stock move.
            rma.setErpSyncStatus("PENDING_SYNC");
            rma.setErpSyncError(null);
        }
        Rma saved = rmaRepository.save(rma);
        recordHistory(id, fromStatus, target, note, principal);

        auditLogService.logAction(principal, "RMA_" + target.name(), "RMA", id.toString(),
                Map.of("status", target.name(), "note", note != null ? note : ""));
        eventPublisher.publishRmaStatusChanged(saved);

        // On restock, push the reverse stock move + note to the ERP.
        if (target == RmaStatus.RESTOCKED) {
            enqueueErpReturn(saved);
        }
        return RmaResponse.from(saved);
    }

    /**
     * Manually re-runs the ERP reverse-move for a return whose sync failed. Mirrors the order-level resync:
     * only a RESTOCKED return that is SYNC_FAILED can be replayed; it goes back to PENDING_SYNC and is
     * re-enqueued through the same transactional outbox, so the async result can flip it to SYNCED.
     */
    @Transactional
    public RmaResponse resync(UUID id, UserPrincipal principal) {
        Rma rma = load(id);
        if (rma.getStatus() != RmaStatus.RESTOCKED) {
            throw AppException.conflict("RMA_NOT_RESTOCKED",
                    "Seul un retour restocké peut être resynchronisé.");
        }
        if (!"SYNC_FAILED".equals(rma.getErpSyncStatus())) {
            throw AppException.conflict("RMA_NOT_FAILED",
                    "Ce retour n'est pas en échec de synchronisation.");
        }
        rma.setErpSyncStatus("PENDING_SYNC");
        rma.setErpSyncError(null);
        Rma saved = rmaRepository.save(rma);

        auditLogService.logAction(principal, "RMA_RESYNC", "RMA", id.toString(),
                Map.of("delivery", String.valueOf(saved.getDeliveryId())));
        enqueueErpReturn(saved);
        return RmaResponse.from(saved);
    }

    private void enqueueErpReturn(Rma rma) {
        if (rma.getErpOrderId() == null) {
            log.info("RMA {} has no erpOrderId — skipping ERP return sync", rma.getId());
            return;
        }
        List<Map<String, Object>> items = rma.getItems().stream().map(i -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sku", i.getSku());
            m.put("name", i.getName());
            m.put("quantity", i.getQuantity());
            m.put("condition", i.getCondition() != null ? i.getCondition().name() : null);
            return m;
        }).toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("deliveryId", rma.getDeliveryId().toString());
        payload.put("rmaId", rma.getId().toString());
        payload.put("reason", rma.getReason());
        payload.put("items", items);
        outboxProcessor.enqueue("ERP_SYNC_RETURN", payload);
    }

    /**
     * D3 — Returns a SKU → delivered-quantity map from the order lines, so a return can be clamped to
     * what was actually delivered. Lines without a SKU are skipped (they can't be matched reliably);
     * an empty map means "no line detail", in which case the caller keeps the requested quantity.
     */
    private Map<String, Integer> deliveredQuantitiesBySku(Order order) {
        Map<String, Integer> delivered = new HashMap<>();
        if (order == null || order.getItems() == null) return delivered;
        for (OrderItem item : order.getItems()) {
            if (item == null || item.getSku() == null || item.getSku().isBlank()) continue;
            int done = item.getQuantityDone() != null ? Math.max(item.getQuantityDone(), 0) : 0;
            delivered.merge(item.getSku().trim(), done, Integer::sum);
        }
        return delivered;
    }

    /** Set/clear the inbound return-shipment tracking (carrier + tracking number). Stamps shippedAt on first set. */
    @Transactional
    public RmaResponse updateShipping(UUID id, String trackingNumber, String shippingCarrier, UserPrincipal principal) {
        Rma rma = load(id);
        String tn = trackingNumber != null && !trackingNumber.isBlank() ? trackingNumber.trim() : null;
        String carrier = shippingCarrier != null && !shippingCarrier.isBlank() ? shippingCarrier.trim() : null;
        rma.setTrackingNumber(tn);
        rma.setShippingCarrier(carrier);
        if (tn != null && rma.getShippedAt() == null) {
            rma.setShippedAt(LocalDateTime.now());
        } else if (tn == null && carrier == null) {
            rma.setShippedAt(null);
        }
        Rma saved = rmaRepository.save(rma);
        auditLogService.logAction(principal, "RMA_SHIPPING", "RMA", id.toString(),
                Map.of("tracking", tn != null ? tn : "", "carrier", carrier != null ? carrier : ""));
        return RmaResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<com.asm.delivery.dto.response.RmaStatusHistoryDto> history(UUID id) {
        load(id); // 404 if the RMA doesn't exist
        return rmaStatusHistoryRepository.findByRmaIdOrderByCreatedAtAsc(id).stream()
                .map(com.asm.delivery.dto.response.RmaStatusHistoryDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> kpi() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (RmaStatus s : RmaStatus.values()) byStatus.put(s.name(), 0L);
        rmaRepository.countGroupedByStatus().forEach(row -> byStatus.put(((RmaStatus) row[0]).name(), (Long) row[1]));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byStatus", byStatus);
        out.put("open", byStatus.get("REQUESTED") + byStatus.get("APPROVED") + byStatus.get("RECEIVED"));
        out.put("restocked", byStatus.get("RESTOCKED"));
        out.put("totalValue", rmaRepository.sumReturnValue());
        return out;
    }

    private Rma load(UUID id) {
        return rmaRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("RMA_NOT_FOUND", "Retour introuvable."));
    }

    /** Allowed forward transitions; REJECTED/CANCELLED reachable from any non-terminal state. */
    private void assertTransition(RmaStatus from, RmaStatus to) {
        Set<RmaStatus> terminal = EnumSet.of(RmaStatus.RESTOCKED, RmaStatus.REJECTED, RmaStatus.CANCELLED);
        if (terminal.contains(from)) {
            throw AppException.conflict("RMA_TERMINAL", "Ce retour est clôturé (" + from + ").",
                    Map.of("from", from.name()));
        }
        if (to == RmaStatus.REJECTED || to == RmaStatus.CANCELLED) return;
        boolean ok = switch (from) {
            case REQUESTED -> to == RmaStatus.APPROVED;
            case APPROVED  -> to == RmaStatus.RECEIVED;
            case RECEIVED  -> to == RmaStatus.RESTOCKED;
            default        -> false;
        };
        if (!ok) {
            throw AppException.conflict("RMA_INVALID_TRANSITION", "Transition invalide : " + from + " → " + to,
                    Map.of("from", from.name(), "to", to.name()));
        }
    }
}
