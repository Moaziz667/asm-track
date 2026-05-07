package com.asm.delivery.controller;

import com.asm.delivery.entity.Company;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService service;

    @GetMapping("/me")
    public ResponseEntity<Company> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal.getCompanyId() == null) return ResponseEntity.noContent().build();
        return service.findById(UUID.fromString(principal.getCompanyId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Company>> list(@AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(service.findAll());
    }

    @PostMapping
    public ResponseEntity<Company> create(@RequestBody Company body,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(service.create(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Company> update(@PathVariable UUID id,
                                          @RequestBody Company body,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Company> uploadLogo(@PathVariable UUID id,
                                              @RequestParam("file") MultipartFile file,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(service.uploadLogo(id, file));
    }

    private void requireSuperAdmin(UserPrincipal principal) {
        if (!"SUPER_ADMIN".equals(principal.getRole())) {
            throw new AccessDeniedException("Super-admin access required");
        }
    }
}
