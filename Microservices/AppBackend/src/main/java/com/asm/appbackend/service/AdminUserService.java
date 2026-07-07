package com.asm.appbackend.service;

import com.asm.appbackend.dto.admin.*;
import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.messaging.AuditEventPublisher;
import com.asm.appbackend.repository.AdminUserRepository;
import com.asm.appbackend.security.KeycloakUserRollbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final AdminUserRepository adminUserRepo;
    private final KeycloakAdminClient keycloakAdminClient;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditEventPublisher auditEventPublisher;
    private final OutboxProcessor outboxProcessor;

    // ── User management (unchanged) ───────────────────────────────────────────

    @Transactional
    public AdminUserResponse createUser(CreateAdminUserRequest req) {
        if (adminUserRepo.existsByEmail(req.email())) {
            throw AppException.conflict("USER_EMAIL_EXISTS", "Email already in use");
        }
        AdminUser user = AdminUser.builder()
                .name(req.name())
                .email(req.email())
                .role(req.role())
                .active(true)
                .build();

        adminUserRepo.save(user);

        // Provision in Keycloak (Resilient: caught exceptions will not roll back database).
        // kcSynced stays false until Keycloak confirms, so the reconciler heals a failed provision.
        try {
            keycloakAdminClient.createUser(req.email(), req.role(), user.getId().toString(), req.password(), req.name());
            eventPublisher.publishEvent(new KeycloakUserRollbackEvent(this, user.getId().toString()));
            user.setKcSynced(true);
            adminUserRepo.save(user);
        } catch (Exception e) {
            log.warn("Keycloak is down/failed to provision user (appUserId={}) during creation. Sync scheduler will reconcile: {}", user.getId(), e.getMessage());
        }

        log.info("Admin user created locally: email={} role={}", req.email(), req.role());
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return adminUserRepo.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * Current user's profile for the UI. The display name is Keycloak-mastered (users self-edit it in
     * the account console), so we read it LIVE from the KC user entity here — NOT from the access token,
     * whose {@code name} claim is a stale login-time snapshot (Keycloak caches it on the session and a
     * refresh-token renew does not re-read it; only a full re-login does). The DB mirror (read by audit
     * attribution) is refreshed in passing. If Keycloak is unreachable, fall back to the mirror.
     */
    @Transactional
    public AdminUserResponse getMeLive(UUID id) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        try {
            Map<String, Object> kc = keycloakAdminClient.getUserByAppUserId(id.toString());
            if (kc != null) {
                String fn = kc.get("firstName") == null ? "" : kc.get("firstName").toString();
                String ln = kc.get("lastName") == null ? "" : kc.get("lastName").toString();
                String live = (fn + " " + ln).trim();
                if (!live.isBlank() && !live.equals(user.getName())) {
                    user.setName(live);
                    adminUserRepo.save(user);
                }
            }
        } catch (Exception e) {
            log.warn("getMeLive: could not read live name from Keycloak for {}: {}", id, e.getMessage());
        }
        return toResponse(user);
    }

    @Transactional
    public AdminUserResponse setActive(UUID id, boolean active) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        user.setActive(active);
        user.setKcSynced(false);
        AdminUser saved = adminUserRepo.save(user);

        // Async, exactly-once via the IAM outbox (drained by OutboxProcessor → Keycloak).
        outboxProcessor.enqueue(IamCommandApplier.SET_ENABLED, java.util.Map.of(
                "appUserId", user.getId().toString(), "enabled", active));

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
            throw AppException.conflict("USER_EMAIL_EXISTS", "Email already in use");
        }

        String oldName = user.getName();
        user.setName(name.trim());
        user.setEmail(trimmedEmail);
        user.setRole(role);
        user.setKcSynced(false); // about to mutate the KC mirror — mark dirty until confirmed
        AdminUser saved = adminUserRepo.save(user);

        // Async, exactly-once via the IAM outbox. Each changed attribute is its own command so a
        // partial failure retries only what's left; the admin reconciler is the backstop.
        String appUserId = user.getId().toString();
        if (!trimmedEmail.equalsIgnoreCase(oldEmail)) {
            outboxProcessor.enqueue(IamCommandApplier.UPDATE_EMAIL, java.util.Map.of(
                    "appUserId", appUserId, "oldEmail", oldEmail, "email", trimmedEmail));
        }
        if (!role.equalsIgnoreCase(oldRole)) {
            outboxProcessor.enqueue(IamCommandApplier.SET_ROLE, java.util.Map.of(
                    "appUserId", appUserId, "email", trimmedEmail, "role", role));
        }
        if (!saved.getName().equals(oldName)) {
            // The display name is Keycloak-mastered (users self-edit it in the account console), so we
            // write it through to KC — the master — directly; the mirror set above just follows. There
            // is intentionally no DB→KC name reconcile (it would revert a user's self-edit).
            keycloakAdminClient.updateUserName(appUserId, trimmedEmail, saved.getName());
        }

        return toResponse(saved);
    }

    @Transactional
    public void forceLogout(UUID id) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        String sub = keycloakAdminClient.forceLogout(user.getId().toString());
        // S2: tell the user's client to log out immediately instead of waiting for token expiry.
        // Keyed on the Keycloak subject (sub) — same key the back-channel-logout path uses.
        auditEventPublisher.publishSessionRevoked(sub);
    }

    @Transactional
    public void resetPasswordEmail(UUID id) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        keycloakAdminClient.triggerPasswordResetEmail(user.getId().toString());
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
