package com.asm.driver.controller;

import com.asm.driver.dto.response.AdminDriverResponse;
import com.asm.driver.security.UserPrincipal;
import com.asm.driver.service.AdminDriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/admin/drivers")
@Tag(name = "Admin Drivers", description = "Super-admin driver management")
@RequiredArgsConstructor
public class AdminDriverController {

    private final AdminDriverService service;

    @GetMapping
    @Operation(summary = "List drivers (company-scoped for ADMIN, all for SUPER_ADMIN)")
    public ResponseEntity<List<AdminDriverResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdminOrSuperAdmin(principal);
        UUID companyId = principal.getCompanyId() != null
                ? UUID.fromString(principal.getCompanyId()) : null;
        return ResponseEntity.ok(service.listAll(companyId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get driver detail")
    public ResponseEntity<AdminDriverResponse> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdminOrSuperAdmin(principal);
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    @Operation(summary = "Invite driver — sends setup email, no password required")
    public ResponseEntity<AdminDriverResponse> invite(
            @RequestBody InviteDriverRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdminOrSuperAdmin(principal);
        UUID companyId = principal.getCompanyId() != null
                ? UUID.fromString(principal.getCompanyId()) : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.invite(req.name(), req.phone(), req.email(), companyId));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update driver info")
    public ResponseEntity<AdminDriverResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateDriverRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdminOrSuperAdmin(principal);
        return ResponseEntity.ok(service.update(id, req.name(), req.phone()));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate driver")
    public ResponseEntity<AdminDriverResponse> setStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdminOrSuperAdmin(principal);
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
        requireAdminOrSuperAdmin(principal);
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isBlank()) return ResponseEntity.badRequest().build();
        service.resetPassword(id, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk import drivers from CSV (name,phone,password)")
    public ResponseEntity<List<AdminDriverResponse>> importCsv(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAdminOrSuperAdmin(principal);
        UUID companyId = principal.getCompanyId() != null
                ? UUID.fromString(principal.getCompanyId()) : null;
        return ResponseEntity.ok(service.importCsv(file, companyId));
    }

    private void requireAdminOrSuperAdmin(UserPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Authentication required");
        String role = principal.getRole();
        if (!"SUPER_ADMIN".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException("Admin access required");
        }
    }

    public record InviteDriverRequest(@NotBlank String name, @NotBlank String phone, @NotBlank String email) {}

    public record UpdateDriverRequest(String name, String phone) {}
}
