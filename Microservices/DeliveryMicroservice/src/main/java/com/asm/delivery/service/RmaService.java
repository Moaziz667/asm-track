package com.asm.delivery.service;

import com.asm.delivery.dto.request.CreateRmaRequest;
import com.asm.delivery.dto.response.RmaResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RmaRepository;
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
    private final DeliveryRepository deliveryRepository;
    private final CompanyRepository companyRepository;
    private final OutboxProcessor outboxProcessor;
    private final AuditLogService auditLogService;

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

        Rma rma = Rma.builder()
                .companyId(companyRepository.findAllByActiveTrue().stream().findFirst().map(Company::getId).orElse(null))
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .blNumber(delivery.getBlNumber() != null ? delivery.getBlNumber() : (order != null ? order.getBlNumber() : null))
                .clientName(order != null ? order.getClientName() : null)
                .status(RmaStatus.REQUESTED)
                .reason(req.getReason())
                .createdBy(principal != null ? principal.getDisplayName() : null)
                .build();

        for (CreateRmaRequest.Item it : req.getItems()) {
            if (it.getQuantity() == null || it.getQuantity() <= 0) continue;
            rma.addItem(RmaItem.builder()
                    .sku(it.getSku())
                    .name(it.getName())
                    .quantity(it.getQuantity())
                    .unitPrice(it.getUnitPrice())
                    .condition(it.getCondition() != null ? it.getCondition() : RmaItemCondition.RESELLABLE)
                    .reason(it.getReason())
                    .build());
        }
        if (rma.getItems().isEmpty()) {
            throw AppException.badRequest("RMA_EMPTY", "Au moins un article avec une quantité valide est requis.");
        }

        Rma saved = rmaRepository.save(rma);
        auditLogService.logAction(principal, "CREATE_RMA", "RMA", saved.getId().toString(),
                Map.of("delivery", String.valueOf(saved.getDeliveryId()), "items", saved.getItems().size()));
        return RmaResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<RmaResponse> list(RmaStatus status) {
        List<Rma> rows = (status != null)
                ? rmaRepository.findByStatusOrderByCreatedAtDesc(status)
                : rmaRepository.findAllByOrderByCreatedAtDesc();
        return rows.stream().map(RmaResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RmaResponse get(UUID id) {
        return RmaResponse.from(load(id));
    }

    @Transactional
    public RmaResponse transition(UUID id, RmaStatus target, String note, UserPrincipal principal) {
        Rma rma = load(id);
        assertTransition(rma.getStatus(), target);

        rma.setStatus(target);
        if (note != null && !note.isBlank()) rma.setResolutionNote(note.trim());
        if (target == RmaStatus.RECEIVED) rma.setReceivedAt(LocalDateTime.now());
        if (target == RmaStatus.RESTOCKED) rma.setRestockedAt(LocalDateTime.now());
        Rma saved = rmaRepository.save(rma);

        auditLogService.logAction(principal, "RMA_" + target.name(), "RMA", id.toString(),
                Map.of("status", target.name(), "note", note != null ? note : ""));

        // On restock, push the reverse stock move + note to the ERP.
        if (target == RmaStatus.RESTOCKED) {
            enqueueErpReturn(saved);
        }
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
            throw AppException.conflict("RMA_TERMINAL", "Ce retour est clôturé (" + from + ").");
        }
        if (to == RmaStatus.REJECTED || to == RmaStatus.CANCELLED) return;
        boolean ok = switch (from) {
            case REQUESTED -> to == RmaStatus.APPROVED;
            case APPROVED  -> to == RmaStatus.RECEIVED;
            case RECEIVED  -> to == RmaStatus.RESTOCKED;
            default        -> false;
        };
        if (!ok) {
            throw AppException.conflict("RMA_INVALID_TRANSITION", "Transition invalide : " + from + " → " + to);
        }
    }
}
