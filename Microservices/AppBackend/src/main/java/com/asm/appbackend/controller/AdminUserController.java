package com.asm.appbackend.controller;

import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.dto.admin.CreateAdminUserRequest;
import com.asm.appbackend.security.UserPrincipal;
import com.asm.appbackend.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final KeycloakAdminClient keycloakAdminClient;
    private final RestTemplate restTemplate;

    @Value("${delivery.service.url:http://delivery-service:8082}")
    private String deliveryServiceUrl;

    private void pushAuditLog(String action, String resourceId, String details) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            String actorId = (auth != null && auth.getName() != null) ? auth.getName() : "Admin";
            String auditUrl = UriComponentsBuilder
                    .fromHttpUrl(deliveryServiceUrl + "/internal/audit")
                    .queryParam("action", action)
                    .queryParam("actorName", actorId)
                    .queryParam("actorRole", "ADMIN")
                    .queryParam("resourceId", resourceId)
                    .queryParam("details", details)
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(keycloakAdminClient.getServiceToken());
            restTemplate.postForEntity(auditUrl, new HttpEntity<>(null, headers), Void.class);
        } catch (Exception e) {
            log.warn("Failed to push audit for {}: {}", action, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<AdminUserResponse> createUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateAdminUserRequest req) {
        AdminUserResponse response = adminUserService.createUser(req);
        pushAuditLog("CREATE_ADMIN_USER", response.id(),
                "Created " + req.role() + " account: " + req.name() + " (" + req.email() + ")");
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
                "Set status of " + res.email() + " to " + (active ? "ACTIVE" : "INACTIVE"));
        return ResponseEntity.ok(res);
    }

    public record UpdateAdminUserRequest(String name, String email, String role) {}

    @PutMapping("/{id}")
    public ResponseEntity<AdminUserResponse> updateUser(
            @PathVariable UUID id,
            @RequestBody UpdateAdminUserRequest req) {
        AdminUserResponse res = adminUserService.updateUser(id, req.name(), req.email(), req.role());
        pushAuditLog("UPDATE_ADMIN_USER", res.id(),
                "Updated account: " + res.name() + " (" + res.email() + "), role: " + res.role());
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
