package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.dto.admin.CreateAdminUserRequest;
import com.asm.appbackend.messaging.AuditEventPublisher;
import com.asm.appbackend.security.UserPrincipal;
import com.asm.appbackend.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('perm:user:manage')") // defense-in-depth; gateway also gates this path
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AuditEventPublisher auditEventPublisher;

    /**
     * Emit an admin-user audit event over RabbitMQ (non-blocking, loss-tolerant). The actor is
     * resolved from the SecurityContext so the persisted audit row names the real dispatcher.
     */
    private void pushAuditLog(String action, String resourceId, String details) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String actorId = (auth != null && auth.getName() != null) ? auth.getName() : "Admin";
        auditEventPublisher.publishAdminUserAudit(action, actorId, "ADMIN", resourceId, details);
    }

    /** Minimal JSON escaping for values spliced into a hand-built audit payload. */
    private static String jsonEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @PostMapping
    public ResponseEntity<AdminUserResponse> createUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateAdminUserRequest req) {
        AdminUserResponse response = adminUserService.createUser(req);
        pushAuditLog("CREATE_ADMIN_USER", response.id(),
                String.format("{\"name\":\"%s\",\"email\":\"%s\",\"role\":\"%s\"}",
                        jsonEsc(req.name()), jsonEsc(req.email()), jsonEsc(req.role())));
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> listUsers() {
        return ResponseEntity.ok(adminUserService.listUsers());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminUserResponse> setStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body) {
        Boolean active = body.get("active");
        if (active == null) return ResponseEntity.badRequest().build();
        AdminUserResponse res = adminUserService.setActive(id, active);
        pushAuditLog("TOGGLE_ADMIN_USER_STATUS", res.id(),
                String.format("{\"email\":\"%s\",\"name\":\"%s\",\"status\":\"%s\"}",
                        jsonEsc(res.email()), jsonEsc(res.name()), active ? "ACTIVE" : "INACTIVE"));
        return ResponseEntity.ok(res);
    }

    public record UpdateAdminUserRequest(String name, String email, String role) {}

    @PutMapping("/{id}")
    public ResponseEntity<AdminUserResponse> updateUser(
            @PathVariable UUID id,
            @RequestBody UpdateAdminUserRequest req) {
        AdminUserResponse res = adminUserService.updateUser(id, req.name(), req.email(), req.role());
        pushAuditLog("UPDATE_ADMIN_USER", res.id(),
                String.format("{\"name\":\"%s\",\"email\":\"%s\",\"role\":\"%s\"}",
                        jsonEsc(res.name()), jsonEsc(res.email()), jsonEsc(res.role())));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/{id}/reset-password-email")
    public ResponseEntity<Map<String, String>> resetPasswordEmail(@PathVariable UUID id) {
        adminUserService.resetPasswordEmail(id);
        pushAuditLog("RESET_ADMIN_USER_PASSWORD", id.toString(),
                "Triggered Keycloak password reset email");
        return ResponseEntity.ok(Map.of("message", "Password reset email triggered successfully"));
    }

    @PostMapping("/{id}/logout")
    public ResponseEntity<Map<String, String>> forceLogout(@PathVariable UUID id) {
        adminUserService.forceLogout(id);
        pushAuditLog("FORCE_LOGOUT_ADMIN_USER", id.toString(),
                "Forced session invalidation in Keycloak");
        return ResponseEntity.ok(Map.of("message", "User force logged out successfully"));
    }
}
