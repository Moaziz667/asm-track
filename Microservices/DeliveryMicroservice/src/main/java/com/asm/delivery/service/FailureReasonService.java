package com.asm.delivery.service;

import com.asm.delivery.dto.request.FailureReasonRequest;
import com.asm.delivery.dto.response.FailureReasonResponse;
import com.asm.delivery.entity.Company;
import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.FailureReason;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.repository.FailureReasonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * CRUD + seeding for the configurable {@link FailureReason} referential.
 * Company context follows the per-instance arch: the single active company.
 */
@Service
@RequiredArgsConstructor
public class FailureReasonService {

    /** Default seed: enum value -> French label. Code == enum name for continuity. */
    private static final List<FailureReason> DEFAULTS = List.of(
            seed("CLIENT_ABSENT", "Client absent", FailureCode.CLIENT_ABSENT, 0),
            seed("REFUSED", "Refus du client", FailureCode.REFUSED, 1),
            seed("WRONG_ADDRESS", "Adresse incorrecte", FailureCode.WRONG_ADDRESS, 2),
            seed("DAMAGED", "Colis endommagé", FailureCode.DAMAGED, 3),
            seed("OTHER", "Autre", FailureCode.OTHER, 4)
    );

    private final FailureReasonRepository repository;
    private final CompanyRepository companyRepository;

    private static FailureReason seed(String code, String label, FailureCode cat, int order) {
        return FailureReason.builder().code(code).label(label).category(cat).active(true).sortOrder(order).build();
    }

    /** Resolve the active company; seed defaults on first use. */
    private UUID resolveCompanyId() {
        Company company = companyRepository.findAllByActiveTrue().stream().findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.PRECONDITION_FAILED,
                        "NO_ACTIVE_COMPANY", "Aucune société active configurée."));
        return company.getId();
    }

    @Transactional
    public List<FailureReasonResponse> listForAdmin() {
        UUID companyId = resolveCompanyId();
        ensureSeeded(companyId);
        return repository.findByCompanyIdOrderBySortOrderAscLabelAsc(companyId).stream()
                .map(FailureReasonResponse::from).toList();
    }

    @Transactional
    public List<FailureReasonResponse> listActive() {
        UUID companyId = resolveCompanyId();
        ensureSeeded(companyId);
        return repository.findByCompanyIdAndActiveTrueOrderBySortOrderAscLabelAsc(companyId).stream()
                .map(FailureReasonResponse::from).toList();
    }

    private void ensureSeeded(UUID companyId) {
        if (repository.countByCompanyId(companyId) > 0) return;
        DEFAULTS.forEach(d -> repository.save(FailureReason.builder()
                .companyId(companyId).code(d.getCode()).label(d.getLabel())
                .category(d.getCategory()).active(true).sortOrder(d.getSortOrder()).build()));
    }

    @Transactional
    public FailureReasonResponse create(FailureReasonRequest req) {
        UUID companyId = resolveCompanyId();
        String code = (req.getCode() != null && !req.getCode().isBlank())
                ? req.getCode().trim().toUpperCase(Locale.ROOT)
                : slugify(req.getLabel());
        if (repository.existsByCompanyIdAndCode(companyId, code)) {
            throw AppException.conflict("FAILURE_REASON_EXISTS",
                    "Un motif avec ce code existe déjà : " + code);
        }
        FailureReason saved = repository.save(FailureReason.builder()
                .companyId(companyId)
                .code(code)
                .label(req.getLabel().trim())
                .category(req.getCategory())
                .active(req.getActive() == null || req.getActive())
                .sortOrder(req.getSortOrder() == null ? 100 : req.getSortOrder())
                .build());
        return FailureReasonResponse.from(saved);
    }

    @Transactional
    public FailureReasonResponse update(UUID id, FailureReasonRequest req) {
        FailureReason reason = getOwned(id);
        reason.setLabel(req.getLabel().trim());
        reason.setCategory(req.getCategory());
        if (req.getActive() != null) reason.setActive(req.getActive());
        if (req.getSortOrder() != null) reason.setSortOrder(req.getSortOrder());
        return FailureReasonResponse.from(repository.save(reason));
    }

    /** Soft-delete: deactivate so historical deliveries keep their reason intact. */
    @Transactional
    public void deactivate(UUID id) {
        FailureReason reason = getOwned(id);
        reason.setActive(false);
        repository.save(reason);
    }

    private FailureReason getOwned(UUID id) {
        UUID companyId = resolveCompanyId();
        FailureReason reason = repository.findById(id)
                .orElseThrow(() -> AppException.notFound("FAILURE_REASON_NOT_FOUND", "Motif introuvable."));
        if (!companyId.equals(reason.getCompanyId())) {
            throw AppException.notFound("FAILURE_REASON_NOT_FOUND", "Motif introuvable.");
        }
        return reason;
    }

    /**
     * Resolve a submitted reason code to its analytics category and label.
     * Falls back to OTHER when the code is unknown (e.g. a deactivated reason).
     */
    @Transactional(readOnly = true)
    public Resolved resolve(String code) {
        if (code == null || code.isBlank()) return new Resolved(FailureCode.OTHER, "Autre");
        UUID companyId = resolveCompanyId();
        return repository.findByCompanyIdAndCode(companyId, code.trim())
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
