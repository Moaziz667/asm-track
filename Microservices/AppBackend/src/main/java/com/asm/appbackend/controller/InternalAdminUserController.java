package com.asm.appbackend.controller;

import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.repository.AdminUserRepository;
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
public class InternalAdminUserController {

    private final AdminUserRepository adminUserRepo;

    public record InternalAdminUserDTO(String id, String name, String role) {}

    @GetMapping("/{id}")
    public ResponseEntity<InternalAdminUserDTO> getById(@PathVariable String id) {
        UUID uuid = parse(id);
        if (uuid == null) return ResponseEntity.notFound().build();
        return adminUserRepo.findById(uuid)
                .map(u -> ResponseEntity.ok(toDto(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Batch resolve — one round-trip for a whole status timeline. Unknown ids are simply omitted. */
    @PostMapping("/by-ids")
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
