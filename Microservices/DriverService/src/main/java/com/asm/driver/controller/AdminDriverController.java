package com.asm.driver.controller;

import com.asm.driver.dto.response.AdminDriverResponse;
import com.asm.driver.security.UserPrincipal;
import com.asm.driver.service.AdminDriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
    @Operation(summary = "List all drivers")
    public ResponseEntity<List<AdminDriverResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get driver detail")
    public ResponseEntity<AdminDriverResponse> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    @Operation(summary = "Create driver account")
    public ResponseEntity<AdminDriverResponse> create(
            @RequestBody CreateDriverRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req.name(), req.phone(), req.password()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update driver info")
    public ResponseEntity<AdminDriverResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateDriverRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(service.update(id, req.name(), req.phone()));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate driver")
    public ResponseEntity<AdminDriverResponse> setStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        Boolean active = body.get("active");
        if (active == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(service.setActive(id, active));
    }

    @PostMapping("/{id}/reset-password")
    @Operation(summary = "Reset driver password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isBlank()) return ResponseEntity.badRequest().build();
        service.resetPassword(id, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    private void requireSuperAdmin(UserPrincipal principal) {
        if (principal == null || !"SUPER_ADMIN".equals(principal.getRole())) {
            throw new AccessDeniedException("Super-admin access required");
        }
    }

    public record CreateDriverRequest(@NotBlank String name, @NotBlank String phone,
                                      @NotBlank @Size(min = 6) String password) {}

    public record UpdateDriverRequest(String name, String phone) {}
}
