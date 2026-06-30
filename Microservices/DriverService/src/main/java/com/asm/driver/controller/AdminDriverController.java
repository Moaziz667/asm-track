package com.asm.driver.controller;

import com.asm.driver.dto.response.AdminDriverResponse;
import com.asm.driver.dto.response.DriverAuditLogResponse;
import com.asm.driver.entity.DriverAuditLog;
import com.asm.driver.exception.AppException;
import com.asm.driver.security.UserPrincipal;
import com.asm.driver.service.AdminDriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/drivers")
@Tag(name = "Admin Drivers", description = "Super-admin driver management")
@RequiredArgsConstructor
public class AdminDriverController {

    private final AdminDriverService service;

    @GetMapping
    @Operation(summary = "List drivers")
    public ResponseEntity<List<AdminDriverResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/avatars")
    @Operation(summary = "driverId → avatar URL map (dispatcher-visible; non-sensitive)")
    public ResponseEntity<Map<String, String>> avatars() {
        // No requireAdmin: dispatchers see driver avatars on the dispatch desk. Authorization is the
        // gateway's driver:view perm + the SecurityConfig rule that permits this GET to any authenticated user.
        return ResponseEntity.ok(service.avatarsMap());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get driver detail")
    public ResponseEntity<AdminDriverResponse> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    @Operation(summary = "Invite driver — sends setup email, no password required")
    public ResponseEntity<AdminDriverResponse> invite(
            @RequestBody InviteDriverRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.invite(req.name(), req.phone(), req.email(), principal));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update driver info")
    public ResponseEntity<AdminDriverResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateDriverRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        return ResponseEntity.ok(service.update(id, req.name(), req.phone(), req.email(), principal));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate driver (PENDING_SETUP is rejected)")
    public ResponseEntity<AdminDriverResponse> setStatus(
            @PathVariable UUID id,
            @RequestBody SetStatusRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        return ResponseEntity.ok(service.setActive(id, req.isRegistered(), req.reason(), principal));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a pending invite (PENDING_SETUP drivers only)")
    public ResponseEntity<Map<String, String>> cancelInvite(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelInviteRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        String reason = req != null ? req.reason() : null;
        service.cancelInvite(id, reason, principal);
        return ResponseEntity.ok(Map.of("message", "Invite cancelled"));
    }

    @PostMapping("/{id}/resend-invite")
    @Operation(summary = "Resend invitation email to a PENDING_SETUP driver")
    public ResponseEntity<Map<String, Object>> resendInvite(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        return ResponseEntity.ok(service.adminResendInvite(id, principal));
    }

    @PostMapping("/{id}/logout")
    @Operation(summary = "Force logout a driver (session invalidation)")
    public ResponseEntity<Map<String, String>> forceLogout(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        service.forceLogout(id, principal);
        return ResponseEntity.ok(Map.of("message", "Driver force logged out successfully"));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk import drivers from CSV (name,phone,password)")
    public ResponseEntity<List<AdminDriverResponse>> importCsv(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        return ResponseEntity.ok(service.importCsv(file, principal));
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "List driver audit logs (paginated, filterable)")
    public ResponseEntity<Map<String, Object>> listAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String actorRole,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        Page<DriverAuditLog> result = service.listAuditLogs(action, actor, actorRole, from, to, page, size);
        List<DriverAuditLogResponse> content = result.getContent().stream()
                .map(this::toAuditDto)
                .toList();
        return ResponseEntity.ok(Map.of(
                "content", content,
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "number", result.getNumber(),
                "size", result.getSize()));
    }

    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "Audit logs for a single driver")
    public ResponseEntity<List<DriverAuditLogResponse>> driverAuditLogs(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal);
        Page<DriverAuditLog> result = service.listAuditLogs(null, null, null, null, null, 0, 50);
        List<DriverAuditLogResponse> content = result.getContent().stream()
                .filter(l -> id.equals(l.getResourceId()))
                .map(this::toAuditDto)
                .toList();
        return ResponseEntity.ok(content);
    }

    private DriverAuditLogResponse toAuditDto(DriverAuditLog l) {
        return DriverAuditLogResponse.builder()
                .id(l.getId())
                .actorId(l.getActorId() != null ? l.getActorId().toString() : null)
                .actorName(l.getActorName())
                .actorRole(l.getActorRole())
                .action(l.getAction())
                .targetEntity("DRIVER")
                .resourceId(l.getResourceId() != null ? l.getResourceId().toString() : null)
                .details(l.getDetails())
                .ipAddress("admin")
                .createdAt(l.getCreatedAt())
                .build();
    }

    private void requireAdmin(UserPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Authentication required");
        String role = principal.getRole();
        if (!"ADMIN".equals(role)) {
            throw AppException.forbidden("Only ADMIN can manage drivers");
        }
    }

    public record InviteDriverRequest(@NotBlank String name, @NotBlank String phone, @NotBlank String email) {}

    public record UpdateDriverRequest(String name, String phone, String email) {}

    public record SetStatusRequest(boolean isRegistered, String reason) {}

    public record CancelInviteRequest(String reason) {}
}
