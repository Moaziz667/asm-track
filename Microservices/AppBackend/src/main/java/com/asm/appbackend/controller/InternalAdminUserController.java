package com.asm.appbackend.controller;

import com.asm.appbackend.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/admin-users")
@RequiredArgsConstructor
public class InternalAdminUserController {

    private final AdminUserRepository adminUserRepo;

    @Value("${internal.secret:asm-internal-2026}")
    private String internalSecret;

    @PostMapping("/deactivate-by-company/{companyId}")
    @Transactional
    public ResponseEntity<Void> deactivateByCompany(
            @PathVariable UUID companyId,
            @RequestHeader("X-Internal-Secret") String secret) {
        if (!internalSecret.equals(secret)) return ResponseEntity.status(403).build();
        adminUserRepo.deactivateByCompanyId(companyId);
        return ResponseEntity.noContent().build();
    }
}
