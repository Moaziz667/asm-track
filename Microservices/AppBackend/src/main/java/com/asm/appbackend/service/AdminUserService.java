package com.asm.appbackend.service;

import com.asm.appbackend.dto.admin.*;
import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.repository.AdminUserRepository;
import com.asm.appbackend.security.KeycloakUserRollbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final AdminUserRepository adminUserRepo;
    private final RestTemplate restTemplate;
    private final KeycloakAdminClient keycloakAdminClient;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${auth.client.id}")
    private String clientId;

    @Value("${auth.client.secret}")
    private String clientSecret;

    // ── User management (unchanged) ───────────────────────────────────────────

    @Transactional
    public AdminUserResponse createUser(CreateAdminUserRequest req) {
        if (adminUserRepo.existsByEmail(req.email())) {
            throw new AppException(HttpStatus.CONFLICT, "Email already in use");
        }
        AdminUser user = AdminUser.builder()
                .name(req.name())
                .email(req.email())
                .role(req.role())
                .active(true)
                .build();

        adminUserRepo.save(user);

        // Provision in Keycloak (Resilient: caught exceptions will not roll back database)
        try {
            keycloakAdminClient.createUser(req.email(), req.role(), user.getId().toString(), req.password());
            eventPublisher.publishEvent(new KeycloakUserRollbackEvent(this, req.email()));
        } catch (Exception e) {
            log.warn("Keycloak is down/failed to provision user (email={}) during creation. Sync scheduler will reconcile: {}", req.email(), e.getMessage());
        }

        log.info("Admin user created locally: email={} role={}", req.email(), req.role());
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return adminUserRepo.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public AdminUserResponse setActive(UUID id, boolean active) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        user.setActive(active);
        AdminUser saved = adminUserRepo.save(user);

        try {
            if (active) {
                keycloakAdminClient.enableUser(user.getEmail());
            } else {
                keycloakAdminClient.disableUser(user.getEmail());
            }
        } catch (Exception e) {
            log.warn("Failed to update user status in Keycloak for email={}. Reconciliation scheduler will retry: {}", user.getEmail(), e.getMessage());
        }

        return toResponse(saved);
    }

    @Transactional
    public AdminUserResponse updateUser(UUID id, String name, String email, String role) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));

        String oldEmail = user.getEmail();
        String oldRole = user.getRole();
        String trimmedEmail = email.trim();

        if (!trimmedEmail.equalsIgnoreCase(oldEmail) && adminUserRepo.existsByEmail(trimmedEmail)) {
            throw new AppException(HttpStatus.CONFLICT, "Email already in use");
        }

        user.setName(name.trim());
        user.setEmail(trimmedEmail);
        user.setRole(role);
        AdminUser saved = adminUserRepo.save(user);

        // Sync to Keycloak resiliently
        try {
            if (!trimmedEmail.equalsIgnoreCase(oldEmail)) {
                keycloakAdminClient.updateUserEmail(oldEmail, trimmedEmail);
            }
            if (!role.equalsIgnoreCase(oldRole)) {
                keycloakAdminClient.setUserRole(trimmedEmail, role);
            }
        } catch (Exception e) {
            log.warn("Failed to sync updates to Keycloak for user: {}. Reconciliation scheduler will retry: {}", trimmedEmail, e.getMessage());
        }

        return toResponse(saved);
    }

    @Transactional
    public void forceLogout(UUID id) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        keycloakAdminClient.forceLogout(user.getEmail());
    }

    @Transactional
    public void resetPasswordEmail(UUID id) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        keycloakAdminClient.triggerPasswordResetEmail(user.getEmail());
    }

    private AdminUserResponse toResponse(AdminUser user) {
        return AdminUserResponse.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
