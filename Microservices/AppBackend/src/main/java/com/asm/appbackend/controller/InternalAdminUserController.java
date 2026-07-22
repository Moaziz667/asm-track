package com.asm.appbackend.controller;

import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.repository.AdminUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service-to-service lookup of admin/dispatcher identities (name + role) by id. Mirrors
 * DriverService's {@code /internal/drivers/{id}}: it lets DeliveryMicroservice turn the admin UUID
 * stored in {@code delivery_status_history.changed_by} into a real person's name for the activity
 * timeline. Guarded by {@code hasRole("SERVICE")} in SecurityConfig ({@code /internal/**}).
 */
@RestController
@RequestMapping("/internal/admin-users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Internal · Admin User Lookup", description = "Service-to-service (SERVICE role) resolution of "
        + "admin/dispatcher identities (name + role) by id — lets other services turn a stored actor UUID into "
        + "a person's name for activity timelines.")
@SecurityRequirement(name = "bearerAuth")
public class InternalAdminUserController {

    private final AdminUserRepository adminUserRepo;

    public record InternalAdminUserDTO(String id, String name, String role) {}

    @GetMapping("/{id}")
    @Operation(summary = "[internal] Resolve one admin user by id",
            description = "Returns the user's id, name and role. Unknown/invalid id → 404.")
    @ApiResponse(responseCode = "200", description = "The user identity")
    public ResponseEntity<InternalAdminUserDTO> getById(@PathVariable String id) {
        UUID uuid = parse(id);
        if (uuid == null) return ResponseEntity.notFound().build();
        return adminUserRepo.findById(uuid)
                .map(u -> ResponseEntity.ok(toDto(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Batch resolve — one round-trip for a whole status timeline. Unknown ids are simply omitted. */
    @PostMapping("/by-ids")
    @Operation(summary = "[internal] Batch-resolve admin users by id",
            description = "Resolves a whole list of ids in one round-trip (e.g. a status timeline). Unknown or "
                    + "invalid ids are simply omitted from the response.")
    @ApiResponse(responseCode = "200", description = "The resolved identities (subset of the input)")
    public List<InternalAdminUserDTO> getByIds(@RequestBody List<String> ids) {
        List<UUID> uuids = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                UUID u = parse(id);
                if (u != null) uuids.add(u);
            }
        }
        if (uuids.isEmpty()) return List.of();
        return adminUserRepo.findAllById(uuids).stream().map(this::toDto).toList();
    }

    private InternalAdminUserDTO toDto(AdminUser u) {
        return new InternalAdminUserDTO(u.getId().toString(), u.getName(), u.getRole());
    }

    private static UUID parse(String id) {
        try { return UUID.fromString(id); } catch (Exception e) { return null; }
    }
}
