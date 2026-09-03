package com.asm.delivery.controller;

import com.asm.delivery.dto.AuditLogView;
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
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Admin Audit", description = "Endpoints for viewing system audit logs")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLogRepository auditLogRepo;

    @GetMapping
    @Operation(summary = "Get paginated and filtered audit logs (every service, one table)")
    public ResponseEntity<Page<AuditLogView>> getLogs(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            String q,
            String action,
            String actor,
            @RequestParam(required = false) List<String> actorRole,
            @RequestParam(required = false) List<String> entity,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);

        // Multi-select: normalize to upper-cased, blank-free lists (empty = no filter).
        final List<String> roles = upperList(actorRole);
        final List<String> entities = upperList(entity);

        // 1. Delivery-side logs (own table)
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Single search box — matches action OR actor OR resource id (case-insensitive).
            if (q != null && !q.isBlank()) {
                String needle = "%" + q.toUpperCase().trim() + "%";
                predicates.add(cb.or(
                        cb.like(cb.upper(root.get("action")), needle),
                        cb.like(cb.upper(root.get("actorName")), needle),
                        cb.like(cb.upper(root.get("resourceId")), needle)));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.like(cb.upper(root.get("action")), "%" + action.toUpperCase().trim() + "%"));
            }
            if (actor != null && !actor.isBlank()) {
                predicates.add(cb.like(cb.upper(root.get("actorName")), "%" + actor.toUpperCase().trim() + "%"));
            }
            if (!roles.isEmpty()) {
                predicates.add(cb.upper(root.get("actorRole")).in(roles));
            }
            if (!entities.isEmpty()) {
                predicates.add(cb.upper(root.get("targetEntity")).in(entities));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null)   predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditLog> deliveryPage = auditLogRepo.findAll(spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        // 2. That is the whole answer. DriverService publishes its events to audit.exchange, exactly
        //    as AppBackend does, and they are persisted in this table before anyone reads it. The
        //    console therefore paginates one indexed table, and the database does the offset.
        //
        //    It used to fetch the driver events over HTTP and merge the two lists here. That merge
        //    could not paginate: the database had already returned the page-th slice, and the merge
        //    then applied the same offset a second time to that slice, so every page past the first
        //    came back empty. Nothing local could fix it either — sorting and cutting across two
        //    sources needs one of them to know the other's rows, which is what a single table is for.
        //    It also made the trail incomplete on a bad day: a driver service that was down took its
        //    events out of the console, silently, at the moment they mattered most.
        return ResponseEntity.ok(deliveryPage.map(this::toView));
    }

    /** Normalize a multi-value param to an upper-cased, blank-free list (null → empty). */
    private static List<String> upperList(List<String> in) {
        if (in == null) return List.of();
        return in.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase())
                .toList();
    }

    private AuditLogView toView(AuditLog l) {
        return AuditLogView.builder()
                .id(l.getId() != null ? l.getId().toString() : null)
                .actorName(l.getActorName())
                .actorRole(l.getActorRole())
                .action(l.getAction())
                .targetEntity(l.getTargetEntity())
                .resourceId(l.getResourceId())
                .details(l.getDetails())
                .ipAddress(l.getIpAddress())
                .createdAt(l.getCreatedAt())
                .build();
    }
}
