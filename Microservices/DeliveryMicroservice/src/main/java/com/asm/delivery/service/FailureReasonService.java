package com.asm.delivery.service;

import com.asm.delivery.dto.request.FailureReasonRequest;
import com.asm.delivery.dto.response.FailureReasonResponse;
import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.FailureReason;
import com.asm.delivery.entity.ReasonScope;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.FailureReasonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * CRUD for the configurable {@link FailureReason} referential (single-tenant — no company scoping).
 * Seeding is owned by the Flyway migration; this service only reads/writes.
 */
@Service
@RequiredArgsConstructor
public class FailureReasonService {

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
        boolean explicitCode = req.getCode() != null && !req.getCode().isBlank();
        String code;
        if (explicitCode) {
            // Caller chose the code (API) — must be unique, no silent rewrite.
            code = req.getCode().trim().toUpperCase(Locale.ROOT);
            if (repository.existsByCode(code)) {
                throw AppException.conflict("FAILURE_REASON_EXISTS",
                        "Un motif avec ce code existe déjà : " + code);
            }
        } else {
            // Auto-generated from the label (the UI path): dedupe so creation never fails on a
            // label collision — the user never sees the code, so a 409 here would be confusing.
            String base = slugify(req.getLabel());
            code = base;
            for (int n = 2; repository.existsByCode(code); n++) {
                code = base + "_" + n;
            }
        }
        FailureReason saved = repository.save(FailureReason.builder()
                .code(code)
                .label(req.getLabel().trim())
                .category(req.getCategory())
                .scope(resolveScope(req))
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
        reason.setScope(resolveScope(req));
        if (req.getActive() != null) reason.setActive(req.getActive());
        if (req.getSortOrder() != null) reason.setSortOrder(req.getSortOrder());
        return FailureReasonResponse.from(repository.save(reason));
    }

    /** Soft-delete: deactivate so historical deliveries keep their reason intact. */
    @Transactional
    public void deactivate(UUID id) {
        FailureReason reason = getOrThrow(id);
        if (!reason.isActive()) return;
        // Guard: never empty a picker. The failure sheet needs ≥1 active DELIVERY motif; each item
        // disposition (a category) needs ≥1 active ITEM motif of that same category; the cash card
        // needs ≥1 active PAYMENT motif, or a driver reporting a shortfall has nothing to select and
        // the collection is filed as unexplained.
        List<FailureReason> active = repository.findByActiveTrueOrderBySortOrderAscLabelAsc();
        if (reason.getScope().coversDelivery()) {
            boolean another = active.stream()
                    .anyMatch(r -> !r.getId().equals(id) && r.getScope().coversDelivery());
            if (!another) {
                throw AppException.conflict("FAILURE_REASON_LAST_DELIVERY",
                        "Impossible de désactiver le dernier motif actif de la fiche d'échec.");
            }
        }
        if (reason.getScope().coversItem()) {
            boolean another = active.stream()
                    .anyMatch(r -> !r.getId().equals(id) && r.getScope().coversItem()
                            && r.getCategory() == reason.getCategory());
            if (!another) {
                throw AppException.conflict("FAILURE_REASON_LAST_ITEM",
                        "Impossible de désactiver le dernier motif article de la catégorie "
                                + reason.getCategory().getLabel() + ".");
            }
        }
        if (reason.getScope().coversPayment()) {
            boolean another = active.stream()
                    .anyMatch(r -> !r.getId().equals(id) && r.getScope().coversPayment());
            if (!another) {
                throw AppException.conflict("FAILURE_REASON_LAST_PAYMENT",
                        "Impossible de désactiver le dernier motif d'encaissement.");
            }
        }
        reason.setActive(false);
        repository.save(reason);
    }

    private FailureReason getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> AppException.notFound("FAILURE_REASON_NOT_FOUND", "Motif introuvable."));
    }

    /**
     * Resolve + validate the scope. ITEM/BOTH is only valid for a per-item disposition category, and
     * PAYMENT only for a refusal (or OTHER, for the awkward money cases: a cheque the driver would
     * not take, a transfer the customer claims to have already made).
     */
    private ReasonScope resolveScope(FailureReasonRequest req) {
        ReasonScope scope = req.getScope() == null ? ReasonScope.DELIVERY : req.getScope();
        if (scope.coversItem() && (req.getCategory() == null || !req.getCategory().isItemDisposition())) {
            throw AppException.badRequest(
                    "La portée « Article » n'est possible que pour les catégories Refusé, Endommagé ou Manquant.");
        }
        if (scope.coversPayment() && req.getCategory() != FailureCode.REFUSED
                && req.getCategory() != FailureCode.OTHER) {
            throw AppException.badRequest(
                    "La portée « Encaissement » n'est possible que pour les catégories Refusé ou Autre.");
        }
        return scope;
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

    /**
     * The catalog label for a motif code, or empty when the code isn't in the catalog (legacy/offline
     * codes). Used to snapshot a human-readable per-item reason at write time.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> findLabel(String code) {
        if (code == null || code.isBlank()) return java.util.Optional.empty();
        return repository.findByCode(code.trim()).map(FailureReason::getLabel);
    }

    private static String slugify(String label) {
        String n = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return n.isBlank() ? "REASON_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT) : n;
    }
}
