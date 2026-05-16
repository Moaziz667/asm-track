package com.asm.appbackend.controller;

import com.asm.appbackend.repository.AdminUserRepository;
import com.asm.appbackend.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/admin-users")
@RequiredArgsConstructor
@Slf4j
public class InternalAdminUserController {

    private final AdminUserRepository adminUserRepo;
    private final JwtService jwtService;

    @PostMapping("/deactivate-by-company/{companyId}")
    @Transactional
    public ResponseEntity<Void> deactivateByCompany(
            @PathVariable UUID companyId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(403).build();
        }
        try {
            Claims claims = jwtService.parseToken(authHeader.substring(7));
            if (!"SERVICE".equals(claims.get("role", String.class))) {
                return ResponseEntity.status(403).build();
            }
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid service token on internal endpoint: {}", e.getMessage());
            return ResponseEntity.status(403).build();
        }

        adminUserRepo.deactivateByCompanyId(companyId);
        return ResponseEntity.noContent().build();
    }
}
