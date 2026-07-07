package com.asm.driver.service;

import com.asm.driver.dto.response.AdminDriverResponse;
import com.asm.driver.dto.response.ActiveMissionsDTO;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverAccountStatus;
import com.asm.driver.entity.DriverAuditLog;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import com.asm.driver.entity.DriverInviteToken;
import com.asm.driver.entity.DriverStats;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverAuditLogRepository;
import com.asm.driver.repository.DriverInviteTokenRepository;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.repository.DriverStatsRepository;
import com.asm.driver.security.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.asm.driver.client.AppBackendIamClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.ApplicationEventPublisher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AdminDriverService {

    private final DriverRepository driverRepo;
    private final DriverStatsRepository statsRepo;
    private final DriverInviteTokenRepository inviteTokenRepo;
    private final DriverAuditLogRepository auditLogRepo;
    private final DriverAuditLogService auditLogService;
    private final EmailService emailService;
    private final DeliveryServiceWebClient deliveryClient;
    private final OutboxProcessor outboxProcessor;
    private final AppBackendIamClient iamClient;
    private final ApplicationEventPublisher eventPublisher;
    private final DriverEventPublisher driverEventPublisher;
    private final com.asm.driver.storage.AvatarService avatarService;

    @Value("${invite.ttl-hours:48}")
    private long inviteTtlHours;

    // ── Queries ─────────────────────────────────────────────────────────────

    /** Lightweight driverId → avatar-URL map for showing photos anywhere a driver appears
     *  (handoff cards, dashboard, stats, reports). Only drivers with a photo are included. */
    @Transactional(readOnly = true)
    public Map<String, String> avatarsMap() {
        Map<String, String> out = new HashMap<>();
        for (Driver d : driverRepo.findAll()) {
            if (d.getPhotoUrl() != null && !d.getPhotoUrl().isBlank()) {
                out.put(d.getId().toString(), d.getPhotoUrl());
            }
        }
        return out;
    }


    public List<AdminDriverResponse> listAll() {
        Map<UUID, ActiveMissionsDTO> missions = deliveryClient.getActiveMissions();
        return driverRepo.findAll().stream().map(d -> toResponse(d, missions)).toList();
    }

    public AdminDriverResponse getById(UUID id) {
        Map<UUID, ActiveMissionsDTO> missions = deliveryClient.getActiveMissions();
        return driverRepo.findById(id)
                .map(d -> toResponse(d, missions))
                .orElseThrow(() -> AppException.notFound("Driver not found"));
    }

    public Page<DriverAuditLog> listAuditLogs(
            String action, String actor, String actorRole,
            LocalDateTime from, LocalDateTime to,
            int page, int size) {

        Specification<DriverAuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (action != null && !action.isBlank()) {
                predicates.add(cb.like(cb.upper(root.get("action")), "%" + action.toUpperCase().trim() + "%"));
            }
            if (actor != null && !actor.isBlank()) {
                predicates.add(cb.like(cb.upper(root.get("actorName")), "%" + actor.toUpperCase().trim() + "%"));
            }
            if (actorRole != null && !actorRole.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("actorRole")), actorRole.toUpperCase().trim()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        return auditLogRepo.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    // ── Invite ──────────────────────────────────────────────────────────────

    @Transactional
    public AdminDriverResponse invite(String name, String phone, String email, UserPrincipal actor) {
        if (driverRepo.existsByPhone(phone)) {
            throw AppException.conflict("DRIVER_PHONE_EXISTS", "Phone already registered");
        }
        if (email == null || email.isBlank()) {
            throw AppException.badRequest("EMAIL_REQUIRED", "Email is required");
        }
        String trimmedEmail = email.trim();
        if (!trimmedEmail.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$")) {
            throw AppException.badRequest("EMAIL_INVALID", "Invalid email format");
        }
        if (driverRepo.existsByEmail(trimmedEmail)) {
            throw AppException.conflict("DRIVER_EMAIL_EXISTS", "Email already registered");
        }

        Driver driver = Driver.builder()
                .name(name)
                .phone(phone)
                .email(trimmedEmail)
                .accountStatus(DriverAccountStatus.PENDING_SETUP)
                .isRegistered(false)
                .build();
        driver = driverRepo.save(driver);

        // Provision the Keycloak user via the IAM outbox (exactly-once). Enqueued in this same
        // transaction as the driver insert, then published to AppBackend (the sole Keycloak owner).
        outboxProcessor.enqueue(OutboxProcessor.IAM_PROVISION, Map.of(
                "appUserId", driver.getId().toString(),
                "email", trimmedEmail,
                "phone", phone,
                "name", name,
                "role", "DRIVER"));

        // Generate invite token
        UUID token = UUID.randomUUID();
        inviteTokenRepo.save(DriverInviteToken.builder()
                .driverId(driver.getId())
                .token(token)
                .expiresAt(LocalDateTime.now().plusHours(inviteTtlHours))
                .build());

        // Send invite email via Resend
        emailService.sendDriverInvite(trimmedEmail, name, token.toString());

        auditLogService.log(
                "DRIVER_INVITED",
                driver.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"phone\":\"%s\",\"email\":\"%s\"}",
                        phone, trimmedEmail));

        return toResponse(driver);
    }

    // ── Setup (driver-side) ─────────────────────────────────────────────────

    @Transactional
    public AdminDriverResponse setupAccount(UUID token, String newPassword) {
        DriverInviteToken invite = inviteTokenRepo.findByToken(token)
                .orElseThrow(() -> AppException.notFound("Invalid or expired invite token"));

        if (invite.isUsed())
            throw AppException.badRequest("This invite has already been used");
        if (invite.getExpiresAt().isBefore(LocalDateTime.now()))
            throw AppException.badRequest("Invite token has expired");

        Driver driver = driverRepo.findById(invite.getDriverId())
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        driver.setAccountStatus(DriverAccountStatus.ACTIVE);
        driver.setIsRegistered(true);
        driverRepo.save(driver);

        invite.setUsed(true);
        inviteTokenRepo.save(invite);

        // Interactive path: set the password + enable SYNCHRONOUSLY via AppBackend (the sole Keycloak
        // owner) so the driver can log in immediately. A failure throws → the whole setup rolls back
        // (token stays unused) so the driver can retry, rather than leaving an ACTIVE driver who can't
        // sign in. This is the one IAM op that must not be eventual.
        iamClient.setPassword(driver.getId().toString(), Map.of("password", newPassword));
        iamClient.setEnabled(driver.getId().toString(), Map.of("enabled", true));

        return toResponse(driver);
    }

    public String validateInviteToken(UUID token) {
        DriverInviteToken invite = inviteTokenRepo.findByToken(token)
                .orElseThrow(() -> AppException.notFound("INVITE_NOT_FOUND"));

        if (invite.isUsed())
            throw AppException.badRequest("INVITE_ALREADY_USED");

        if (invite.getExpiresAt().isBefore(LocalDateTime.now()))
            throw AppException.badRequest("INVITE_EXPIRED");

        Driver driver = driverRepo.findById(invite.getDriverId())
                .orElseThrow(() -> AppException.notFound("DRIVER_NOT_FOUND"));

        return driver.getName();
    }

    // ── Resend invite ───────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> resendActivationCode(String phone) {
        Driver driver = driverRepo.findByPhone(phone)
                .orElseThrow(() -> AppException.notFound("PHONE_NOT_FOUND"));

        if (driver.getAccountStatus() == DriverAccountStatus.ACTIVE) {
            throw AppException.badRequest("DRIVER_ALREADY_ACTIVATED");
        }
        if (driver.getAccountStatus() == DriverAccountStatus.SUSPENDED) {
            throw AppException.badRequest("DRIVER_ACCOUNT_SUSPENDED");
        }

        DriverInviteToken existing = inviteTokenRepo.findByDriverId(driver.getId()).stream()
                .filter(t -> !t.isUsed() && t.getExpiresAt().isAfter(LocalDateTime.now()))
                .findFirst()
                .orElse(null);

        LocalDateTime expiresAt;
        String status;
        if (existing != null) {
            emailService.sendDriverInvite(driver.getEmail(), driver.getName(), existing.getToken().toString());
            expiresAt = existing.getExpiresAt();
            status = "RESENT";
        } else {
            UUID newToken = UUID.randomUUID();
            expiresAt = LocalDateTime.now().plusHours(inviteTtlHours);
            inviteTokenRepo.save(DriverInviteToken.builder()
                    .driverId(driver.getId())
                    .token(newToken)
                    .expiresAt(expiresAt)
                    .build());
            emailService.sendDriverInvite(driver.getEmail(), driver.getName(), newToken.toString());
            status = "SENT";
        }

        return Map.of(
                "expiresAt", expiresAt.toString(),
                "status", status
        );
    }

    /** Admin-triggered resend: looks up driver by id then delegates to Keycloak. */
    @Transactional
    public Map<String, Object> adminResendInvite(UUID id, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        
        if (driver.getAccountStatus() == DriverAccountStatus.ACTIVE) {
            throw AppException.badRequest("DRIVER_ALREADY_ACTIVATED");
        }

        // Resend invitation email via Resend email service
        resendActivationCode(driver.getPhone());
        
        auditLogService.log(
                "DRIVER_INVITE_RESENT_BY_ADMIN",
                driver.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"phone\":\"%s\",\"status\":\"RESENT_VIA_RESEND\"}",
                        driver.getPhone()));

        return Map.of(
                "status", "RESENT"
        );
    }

    // ── Update profile ──────────────────────────────────────────────────────

    @Transactional
    public AdminDriverResponse update(UUID id, String name, String phone, String email, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        if (driver.getAccountStatus() == DriverAccountStatus.PENDING_SETUP) {
            throw AppException.badRequest(
                    "Cannot edit a driver with pending setup — cancel the invite or wait for activation");
        }

        String oldName = driver.getName();
        String oldPhone = driver.getPhone();
        String oldEmail = driver.getEmail();
        boolean nameChanged = false;
        if (name != null && !name.isBlank() && !name.trim().equals(driver.getName())) {
            driver.setName(name.trim());
            nameChanged = true;
        }
        if (phone != null && !phone.isBlank()) {
            if (!phone.equals(driver.getPhone()) && driverRepo.existsByPhone(phone)) {
                throw AppException.conflict("DRIVER_PHONE_EXISTS", "Phone already in use");
            }
            driver.setPhone(phone.trim());
        }
        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(driver.getEmail())) {
            String trimmedEmail = email.trim();
            if (!trimmedEmail.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$")) {
                throw AppException.badRequest("EMAIL_INVALID", "Invalid email format");
            }
            if (driverRepo.existsByEmail(trimmedEmail)) {
                throw AppException.conflict("DRIVER_EMAIL_EXISTS", "Email already in use");
            }
            driver.setEmail(trimmedEmail);
            outboxProcessor.enqueue(OutboxProcessor.IAM_UPDATE_EMAIL, Map.of(
                    "appUserId", driver.getId().toString(), "oldEmail", oldEmail, "email", trimmedEmail));
        }
        // Propagate a renamed driver to Keycloak so audit/history attribution stays accurate.
        if (nameChanged) {
            outboxProcessor.enqueue(OutboxProcessor.IAM_UPDATE_NAME, Map.of(
                    "appUserId", driver.getId().toString(), "email", driver.getEmail(), "name", driver.getName()));
        }
        Driver saved = driverRepo.save(driver);

        auditLogService.log(
                "DRIVER_UPDATED",
                saved.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"oldName\":\"%s\",\"newName\":\"%s\",\"oldPhone\":\"%s\",\"newPhone\":\"%s\",\"oldEmail\":\"%s\",\"newEmail\":\"%s\"}",
                        oldName, saved.getName(), oldPhone, saved.getPhone(), oldEmail, saved.getEmail()));

        return toResponse(saved);
    }

    @Transactional
    public AdminDriverResponse setActive(UUID id, boolean isRegistered, String reason, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        if (driver.getAccountStatus() == DriverAccountStatus.PENDING_SETUP) {
            throw AppException.badRequest("Cannot toggle status of a driver with pending setup");
        }
        DriverAccountStatus previous = driver.getAccountStatus();
        DriverAccountStatus next = isRegistered ? DriverAccountStatus.ACTIVE : DriverAccountStatus.SUSPENDED;
        driver.setAccountStatus(next);
        driver.setIsRegistered(isRegistered);
        
        if (isRegistered) {
            driver.setSuspendedReason(null);
        } else {
            driver.setSuspendedReason(reason);
        }
        // Enable/disable the Keycloak account via the IAM outbox (exactly-once).
        outboxProcessor.enqueue(OutboxProcessor.IAM_SET_ENABLED, Map.of(
                "appUserId", driver.getId().toString(), "enabled", isRegistered));

        Driver saved = driverRepo.save(driver);

        String action = isRegistered ? "DRIVER_ACTIVATED" : "DRIVER_SUSPENDED";
        auditLogService.log(
                action,
                saved.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"previousStatus\":\"%s\",\"newStatus\":\"%s\",\"reason\":\"%s\"}",
                        previous, next, reason != null ? reason : ""));

        return toResponse(saved);
    }

    // ── Cancel pending invite (hard-delete for PENDING_SETUP only) ─────────

    @Transactional
    public void cancelInvite(UUID id, String reason, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        if (driver.getAccountStatus() != DriverAccountStatus.PENDING_SETUP) {
            throw AppException.badRequest("Only pending invites can be cancelled");
        }

        // Delete the Keycloak user via the IAM outbox (exactly-once; survives the driver row delete).
        outboxProcessor.enqueue(OutboxProcessor.IAM_DELETE, Map.of("appUserId", driver.getId().toString()));

        inviteTokenRepo.findByDriverId(id).forEach(t -> {
            t.setUsed(true);
            inviteTokenRepo.save(t);
        });

        driverRepo.delete(driver);

        auditLogService.log(
                "DRIVER_INVITE_CANCELLED",
                id,
                actorName(actor), actorRole(actor),
                String.format("{\"phone\":\"%s\",\"reason\":\"%s\"}", 
                        driver.getPhone(), reason != null ? reason : ""));
    }

    // ── Import ──────────────────────────────────────────────────────────────

    // CSV format: name,phone,email  (header row is skipped)
    public List<AdminDriverResponse> importCsv(MultipartFile file, UserPrincipal actor) {
        List<AdminDriverResponse> created = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] cols = line.split(",", -1);
                if (cols.length < 3) { skipped++; continue; }
                String name  = cols[0].trim();
                String phone = cols[1].trim();
                String email = cols[2].trim();
                if (name.isBlank() || phone.isBlank() || email.isBlank()) { skipped++; continue; }
                if (driverRepo.existsByPhone(phone) || driverRepo.existsByEmail(email)) { skipped++; continue; }
                try {
                    // Reuse the full invite flow: creates driver + Keycloak account + sends email
                    AdminDriverResponse resp = invite(name, phone, email, actor);
                    created.add(resp);
                } catch (Exception e) {
                    log.warn("CSV import: skipping row (name={}, phone={}): {}", name, phone, e.getMessage());
                    skipped++;
                }
            }
        } catch (Exception e) {
            throw AppException.badRequest("CSV parsing failed: " + e.getMessage());
        }

        auditLogService.log(
                "DRIVER_BULK_IMPORTED",
                null,
                actorName(actor), actorRole(actor),
                String.format("{\"fileName\":\"%s\",\"created\":%d,\"skipped\":%d}",
                        file.getOriginalFilename(), created.size(), skipped));

        return created;
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────

    private AdminDriverResponse toResponse(Driver d) {
        return toResponse(d, Collections.emptyMap());
    }

    private AdminDriverResponse toResponse(Driver d, Map<UUID, ActiveMissionsDTO> missions) {
        DriverStats stats = statsRepo.findByDriverId(d.getId()).orElse(null);

        DriverInviteToken latestInvite = inviteTokenRepo.findByDriverId(d.getId()).stream()
                .filter(t -> !t.isUsed() && t.getExpiresAt().isAfter(LocalDateTime.now()))
                .findFirst()
                .orElse(null);

        DriverAuditLog inviteEvent = auditLogRepo
                .findAll((root, q, cb) -> cb.and(
                        cb.equal(root.get("resourceId"), d.getId()),
                        cb.equal(root.get("action"), "DRIVER_INVITED")))
                .stream()
                .findFirst()
                .orElse(null);

        ActiveMissionsDTO mission = missions.get(d.getId());
        String activeDeliveryId = (mission != null && mission.getActiveDeliveryId() != null)
                ? mission.getActiveDeliveryId().toString() : null;
        String activeRouteId = (mission != null && mission.getActiveRouteId() != null)
                ? mission.getActiveRouteId().toString() : null;

        return AdminDriverResponse.builder()
                .id(d.getId().toString())
                .name(d.getName())
                .phone(d.getPhone())
                .isRegistered(d.getIsRegistered())
                .accountStatus(d.getAccountStatus() != null ? d.getAccountStatus().name() : "PENDING_SETUP")
                .currentLat(d.getCurrentLat())
                .currentLng(d.getCurrentLng())
                .lastLocationAt(d.getLastLocationAt())
                .createdAt(d.getCreatedAt())
                .totalDeliveries(stats != null ? stats.getTotalDeliveries() : 0)
                .delivered(stats != null ? stats.getDelivered() : 0)
                .failed(stats != null ? stats.getFailed() : 0)
                .onlineStatus(d.getOnlineStatus() != null ? d.getOnlineStatus().name() : "OFFLINE")
                .email(d.getEmail())
                .photoUrl(d.getPhotoUrl() != null && d.getPhotoVersion() != null && d.getPhotoVersion() > 0
                        ? avatarService.publicUrlFor(d.getId(), d.getPhotoVersion(), "thumb")
                        : null)
                .activeDeliveryId(activeDeliveryId)
                .activeRouteId(activeRouteId)
                .suspendedReason(d.getSuspendedReason())
                .invitationExpiresAt(latestInvite != null ? latestInvite.getExpiresAt() : null)
                .lastInvitedAt(inviteEvent != null ? inviteEvent.getCreatedAt() : null)
                .invitedByName(inviteEvent != null ? inviteEvent.getActorName() : null)
                .build();
    }

    private String actorName(UserPrincipal p) {
        return p != null && p.getUserId() != null ? p.getUserId() : "SYSTEM";
    }

    private String actorRole(UserPrincipal p) {
        return p != null && p.getRole() != null ? p.getRole() : "SYSTEM";
    }

    @Transactional
    public void forceLogout(UUID id, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        // Revoke Keycloak sessions via the IAM outbox; the instant app-logout push below is the
        // immediate backstop, so eventual revocation here is fine.
        outboxProcessor.enqueue(OutboxProcessor.IAM_LOGOUT, Map.of("appUserId", driver.getId().toString()));

        // S2: push an instant logout to the driver's app instead of waiting for token expiry.
        driverEventPublisher.publishSessionRevoked(driver.getId());

        auditLogService.log(
                "DRIVER_FORCE_LOGOUT",
                driver.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"phone\":\"%s\",\"email\":\"%s\"}",
                        driver.getPhone(), driver.getEmail()));
    }
}
