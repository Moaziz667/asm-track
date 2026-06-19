package com.asm.delivery.service;

import com.asm.delivery.dto.request.FailureReasonRequest;
import com.asm.delivery.dto.response.FailureReasonResponse;
import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.FailureContext;
import com.asm.delivery.entity.FailureReason;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.FailureReasonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD for the configurable {@link FailureReason} referential (single-tenant — no company scoping).
 * Seeding is owned by the Flyway migration; this service only reads/writes.
 */
@Service
@RequiredArgsConstructor
public class FailureReasonService {

    /** Contexts an item-outcome picker depends on — must never be left without an active motif. */
    private static final Set<FailureContext> GUARDED_CONTEXTS = EnumSet.of(
            FailureContext.FAILURE, FailureContext.ITEM_REFUSED,
            FailureContext.ITEM_DAMAGED, FailureContext.ITEM_MISSING);

    private final FailureReasonRepository repository;

    @Transactional(readOnly = true)
    public List<FailureReasonResponse> listForAdmin() {
        return repository.findAllByOrderBySortOrderAscLabelAsc().stream()
                .map(FailureReasonResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<FailureReasonResponse> listActive() {
        return repository.findByActiveTrueOrderBySortOrderAscLabelAsc().stream()
                .map(FailureReasonResponse::from).toList();
    }

    @Transactional
    public FailureReasonResponse create(FailureReasonRequest req) {
        String code = (req.getCode() != null && !req.getCode().isBlank())
                ? req.getCode().trim().toUpperCase(Locale.ROOT)
                : slugify(req.getLabel());
        if (repository.existsByCode(code)) {
            throw AppException.conflict("FAILURE_REASON_EXISTS",
                    "Un motif avec ce code existe déjà : " + code);
        }
        FailureReason saved = repository.save(FailureReason.builder()
                .code(code)
                .label(req.getLabel().trim())
                .category(req.getCategory())
                .appliesTo(resolveAppliesTo(req))
                .active(req.getActive() == null || req.getActive())
                .sortOrder(req.getSortOrder() == null ? 100 : req.getSortOrder())
                .build());
        return FailureReasonResponse.from(saved);
    }

    @Transactional
    public FailureReasonResponse update(UUID id, FailureReasonRequest req) {
        FailureReason reason = getOrThrow(id);
        reason.setLabel(req.getLabel().trim());
        reason.setCategory(req.getCategory());
        reason.setAppliesTo(resolveAppliesTo(req));
        if (req.getActive() != null) reason.setActive(req.getActive());
        if (req.getSortOrder() != null) reason.setSortOrder(req.getSortOrder());
        return FailureReasonResponse.from(repository.save(reason));
    }

    /** Soft-delete: deactivate so historical deliveries keep their reason intact. */
    @Transactional
    public void deactivate(UUID id) {
        FailureReason reason = getOrThrow(id);
        if (!reason.isActive()) return;
        // Guard: a context an item/failure picker relies on must keep at least one active motif.
        List<FailureReason> active = repository.findByActiveTrueOrderBySortOrderAscLabelAsc();
        for (FailureContext ctx : reason.getAppliesTo()) {
            if (!GUARDED_CONTEXTS.contains(ctx)) continue;
            boolean another = active.stream()
                    .anyMatch(r -> !r.getId().equals(id) && r.getAppliesTo().contains(ctx));
            if (!another) {
                throw AppException.conflict("FAILURE_REASON_LAST_IN_CONTEXT",
                        "Impossible de désactiver le dernier motif actif du contexte " + ctx + ".");
            }
        }
        reason.setActive(false);
        repository.save(reason);
    }

    private FailureReason getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> AppException.notFound("FAILURE_REASON_NOT_FOUND", "Motif introuvable."));
    }

    private Set<FailureContext> resolveAppliesTo(FailureReasonRequest req) {
        if (req.getAppliesTo() == null || req.getAppliesTo().isEmpty()) {
            return EnumSet.of(FailureContext.FAILURE);
        }
        return EnumSet.copyOf(req.getAppliesTo());
    }

    /**
     * Resolve a submitted reason code to its analytics category and label.
     * Falls back to OTHER when the code is unknown (e.g. a deactivated reason).
     */
    @Transactional(readOnly = true)
    public Resolved resolve(String code) {
        if (code == null || code.isBlank()) return new Resolved(FailureCode.OTHER, "Autre");
        return repository.findByCode(code.trim())
                .map(r -> new Resolved(r.getCategory(), r.getLabel()))
                .orElseGet(() -> {
                    // Tolerate raw enum names submitted by legacy clients.
                    try {
                        return new Resolved(FailureCode.valueOf(code.trim().toUpperCase(Locale.ROOT)), code.trim());
                    } catch (IllegalArgumentException ex) {
                        return new Resolved(FailureCode.OTHER, code.trim());
                    }
                });
    }

    public record Resolved(FailureCode category, String label) {}

    private static String slugify(String label) {
        String n = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return n.isBlank() ? "REASON_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT) : n;
    }
}
