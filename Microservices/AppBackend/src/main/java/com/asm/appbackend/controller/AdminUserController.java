package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.dto.admin.CreateAdminUserRequest;
import com.asm.appbackend.messaging.AuditEventPublisher;
import com.asm.appbackend.security.UserPrincipal;
import com.asm.appbackend.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('perm:user:manage')") // defense-in-depth; gateway also gates this path
@Tag(name = "Admin · Users", description = "Manage back-office user accounts (admins, dispatchers, managers) "
        + "for the caller's company. Every endpoint requires the perm:user:manage permission and is scoped "
        + "to the caller's tenant. Users are mirrored into Keycloak (identity) and the tenant schema.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
        @ApiResponse(responseCode = "403", description = "Caller lacks perm:user:manage")
})
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
    @Operation(summary = "Create a back-office user",
            description = "Creates a user in the caller's company, provisions it in Keycloak (with the given "
                    + "role and a temporary password), assigns it to the company's Keycloak organization, and "
                    + "records an audit event. Idempotent on email within the tenant.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User created"),
            @ApiResponse(responseCode = "400", description = "Validation error (missing/invalid fields)"),
            @ApiResponse(responseCode = "409", description = "Email already in use in this company")
    })
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
    @Operation(summary = "List back-office users",
            description = "Returns all back-office users of the caller's company.")
    @ApiResponse(responseCode = "200", description = "List of users")
    public ResponseEntity<List<AdminUserResponse>> listUsers() {
        return ResponseEntity.ok(adminUserService.listUsers());
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a user",
            description = "Enables or disables the account. Body: {\"active\": true|false}. A disabled user "
                    + "keeps its data but can no longer authenticate. Change propagates to Keycloak.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated"),
            @ApiResponse(responseCode = "400", description = "Missing 'active' field"),
            @ApiResponse(responseCode = "404", description = "User not found in this company")
    })
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
    @Operation(summary = "Update a user's name, email or role",
            description = "Updates the profile fields and syncs the change to Keycloak (email/role). "
                    + "Renaming the role changes the user's effective permissions on next login.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated"),
            @ApiResponse(responseCode = "404", description = "User not found in this company"),
            @ApiResponse(responseCode = "409", description = "New email already in use")
    })
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
    @Operation(summary = "Send a password-reset email",
            description = "Triggers a Keycloak 'update password' action email to the user. Returns 502 if the "
                    + "Keycloak SMTP server is not configured/reachable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset email triggered"),
            @ApiResponse(responseCode = "502", description = "Keycloak SMTP not configured/reachable")
    })
    public ResponseEntity<Map<String, String>> resetPasswordEmail(@PathVariable UUID id) {
        adminUserService.resetPasswordEmail(id);
        pushAuditLog("RESET_ADMIN_USER_PASSWORD", id.toString(),
                "Triggered Keycloak password reset email");
        return ResponseEntity.ok(Map.of("message", "Password reset email triggered successfully"));
    }

    @PostMapping("/{id}/logout")
    @Operation(summary = "Force-logout a user",
            description = "Invalidates the user's Keycloak sessions immediately and broadcasts a session-revoked "
                    + "event so the user's open clients log out without waiting for token expiry.")
    @ApiResponse(responseCode = "200", description = "Sessions invalidated")
    public ResponseEntity<Map<String, String>> forceLogout(@PathVariable UUID id) {
        adminUserService.forceLogout(id);
        pushAuditLog("FORCE_LOGOUT_ADMIN_USER", id.toString(),
                "Forced session invalidation in Keycloak");
        return ResponseEntity.ok(Map.of("message", "User force logged out successfully"));
    }
}
