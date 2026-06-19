package com.asm.delivery.controller;

import com.asm.delivery.dto.request.FailureReasonRequest;
import com.asm.delivery.dto.response.FailureReasonResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.FailureReasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/failure-reasons")
@Tag(name = "Admin Failure Reasons", description = "Configurable delivery-failure reasons (referential)")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminFailureReasonController {

    private final FailureReasonService service;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List failure reasons", description = "active=true returns only active reasons (for dropdowns); otherwise all.")
    public ResponseEntity<List<FailureReasonResponse>> list(
            @RequestParam(required = false, defaultValue = "false") boolean active) {
        return ResponseEntity.ok(active ? service.listActive() : service.listForAdmin());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a failure reason")
    public ResponseEntity<FailureReasonResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody FailureReasonRequest request) {
        FailureReasonResponse created = service.create(request);
        auditLogService.logAction(principal, "CREATE_FAILURE_REASON", "FAILURE_REASON", created.getId().toString(),
                Map.of("code", created.getCode(), "label", created.getLabel()));
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a failure reason")
    public ResponseEntity<FailureReasonResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody FailureReasonRequest request) {
        FailureReasonResponse updated = service.update(id, request);
        auditLogService.logAction(principal, "UPDATE_FAILURE_REASON", "FAILURE_REASON", id.toString(),
                Map.of("label", updated.getLabel(), "active", updated.isActive()));
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a failure reason", description = "Soft-delete: keeps historical deliveries intact.")
    public ResponseEntity<Void> deactivate(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        service.deactivate(id);
        auditLogService.logAction(principal, "DEACTIVATE_FAILURE_REASON", "FAILURE_REASON", id.toString(), Map.of());
        return ResponseEntity.noContent().build();
    }
}
