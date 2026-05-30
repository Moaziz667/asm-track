package com.asm.delivery.controller;

import com.asm.delivery.entity.Company;
import com.asm.delivery.service.CompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/companies")
@Tag(name = "Company", description = "Company configuration and branding management")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService service;
    
    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user's company information")
    public ResponseEntity<Company> getCompany() {
        return getOrInitCompany().map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/me")
    @Operation(summary = "Update the single-tenant company branding details")
    public ResponseEntity<Company> updateCompany(@RequestBody Company patch) {
        Company current = getOrInitCompany().orElseThrow();
        Company updated = service.update(current.getId(), patch);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/me/logo")
    @Operation(summary = "Upload the company logo (multipart/form-data)")
    public ResponseEntity<Company> uploadLogo(@RequestParam("file") MultipartFile file) {
        Company current = getOrInitCompany().orElseThrow();
        Company updated = service.uploadLogo(current.getId(), file);
        return ResponseEntity.ok(updated);
    }

    private java.util.Optional<Company> getOrInitCompany() {
        var list = service.findAll();
        if (!list.isEmpty()) {
            return java.util.Optional.of(list.get(0));
        }
        // If no company exists, create a default one
        Company c = new Company();
        c.setId(UUID.randomUUID());
        c.setName("ASM Logistics");
        c.setActive(true);
        return java.util.Optional.of(service.create(c));
    }
}
