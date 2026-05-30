package com.asm.delivery.controller;

import com.asm.delivery.entity.AuditLog;
import com.asm.delivery.repository.AuditLogRepository;
import com.asm.delivery.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit")
@Tag(name = "Admin Audit", description = "Endpoints for viewing system audit logs")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @Operation(summary = "Get paginated and filtered audit logs")
    public ResponseEntity<Page<AuditLog>> getLogs(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            String action,
            String actor,
            String actorRole,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (action != null && !action.isBlank()) {
                predicates.add(cb.like(cb.upper(root.get("action")), "%" + action.toUpperCase().trim() + "%"));
            }
            if (actor != null && !actor.isBlank()) {
                predicates.add(cb.like(cb.upper(root.get("actorName")), "%" + actor.toUpperCase().trim() + "%"));
            }
            if (actorRole != null && !actorRole.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("actorRole")), actorRole.toUpperCase().trim()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        return ResponseEntity.ok(auditLogRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }
}
