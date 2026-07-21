package com.asm.delivery.controller;

import com.asm.delivery.dto.AuditLogView;
import com.asm.delivery.entity.AuditLog;
import com.asm.delivery.repository.AuditLogRepository;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.DriverAuditClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Admin Audit", description = "Endpoints for viewing system audit logs")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLogRepository auditLogRepo;
    private final DriverAuditClient driverAuditClient;

    @GetMapping
    @Operation(summary = "Get paginated and filtered audit logs (delivery + driver)")
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

        // 2. Driver-side logs via HTTP — fetch a wider window so the page has
        //    at least one driver's events even if delivery is the dominant source.
        // Driver-side search: the single `q` is passed as the action filter (best-effort across the
        // HTTP boundary), falling back to an explicit `action`. Role/entity lists are applied in-memory
        // after fetch (the HTTP contract only carries a single role, so we forward it only when the
        // filter is a single value and otherwise post-filter locally).
        String driverSearch = (q != null && !q.isBlank()) ? q : action;
        String singleRole = roles.size() == 1 ? roles.get(0) : null;
        List<AuditLogView> driverLogs = driverAuditClient.fetchDriverLogs(
                driverSearch, actor, singleRole, from, to, 0, Math.max(safeSize, 200));
        if (!roles.isEmpty()) {
            driverLogs = driverLogs.stream()
                    .filter(v -> v.getActorRole() != null && roles.contains(v.getActorRole().toUpperCase()))
                    .toList();
        }
        if (!entities.isEmpty()) {
            driverLogs = driverLogs.stream()
                    .filter(v -> v.getTargetEntity() != null && entities.contains(v.getTargetEntity().toUpperCase()))
                    .toList();
        }

        // 3. Map + merge
        List<AuditLogView> merged = new ArrayList<>();
        for (AuditLog l : deliveryPage.getContent()) {
            merged.add(toView(l));
        }
        merged.addAll(driverLogs);

        merged.sort(Comparator.comparing(AuditLogView::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        long totalElements = deliveryPage.getTotalElements() + driverLogs.size();
        int totalPages = (int) Math.max(1, Math.ceil((double) merged.size() / safeSize));
        int fromIdx = Math.min(safePage * safeSize, merged.size());
        int toIdx   = Math.min(fromIdx + safeSize, merged.size());
        List<AuditLogView> pageContent = merged.subList(fromIdx, toIdx);

        return ResponseEntity.ok(new PageImpl<>(pageContent,
                PageRequest.of(safePage, safeSize), totalElements));
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
