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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminDriverService {

    private final DriverRepository driverRepo;
    private final DriverStatsRepository statsRepo;
    private final DriverInviteTokenRepository inviteTokenRepo;
    private final DriverAuditLogRepository auditLogRepo;
    private final DriverAuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final DeliveryServiceWebClient deliveryClient;

    @Value("${invite.ttl-hours:48}")
    private long inviteTtlHours;

    // ── Queries ─────────────────────────────────────────────────────────────

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
            throw AppException.conflict("Phone already registered");
        }
        Driver driver = Driver.builder()
                .name(name)
                .phone(phone)
                .email(email)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .accountStatus(DriverAccountStatus.PENDING_SETUP)
                .build();
        driver = driverRepo.save(driver);

        UUID token = UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(inviteTtlHours);
        inviteTokenRepo.save(DriverInviteToken.builder()
                .driverId(driver.getId())
                .token(token)
                .expiresAt(expiresAt)
                .build());

        emailService.sendDriverInvite(email, name, token.toString());

        auditLogService.log(
                "DRIVER_INVITED",
                driver.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"phone\":\"%s\",\"email\":\"%s\",\"expiresAt\":\"%s\"}",
                        phone, email, expiresAt));

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

        driver.setPasswordHash(passwordEncoder.encode(newPassword));
        driver.setAccountStatus(DriverAccountStatus.ACTIVE);
        driverRepo.save(driver);

        invite.setUsed(true);
        inviteTokenRepo.save(invite);

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

    /** Admin-triggered resend: looks up driver by id then delegates. */
    @Transactional
    public Map<String, Object> adminResendInvite(UUID id, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        Map<String, Object> result = resendActivationCode(driver.getPhone());

        auditLogService.log(
                "DRIVER_INVITE_RESENT_BY_ADMIN",
                driver.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"phone\":\"%s\",\"status\":\"%s\",\"expiresAt\":\"%s\"}",
                        driver.getPhone(), result.get("status"), result.get("expiresAt")));

        return result;
    }

    // ── Update profile ──────────────────────────────────────────────────────

    @Transactional
    public AdminDriverResponse update(UUID id, String name, String phone, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        if (driver.getAccountStatus() == DriverAccountStatus.PENDING_SETUP) {
            throw AppException.badRequest(
                    "Cannot edit a driver with pending setup — cancel the invite or wait for activation");
        }

        String oldName = driver.getName();
        String oldPhone = driver.getPhone();
        if (name != null && !name.isBlank()) driver.setName(name.trim());
        if (phone != null && !phone.isBlank()) {
            if (!phone.equals(driver.getPhone()) && driverRepo.existsByPhone(phone)) {
                throw AppException.conflict("Phone already in use");
            }
            driver.setPhone(phone.trim());
        }
        Driver saved = driverRepo.save(driver);

        auditLogService.log(
                "DRIVER_UPDATED",
                saved.getId(),
                actorName(actor), actorRole(actor),
                String.format("{\"oldName\":\"%s\",\"newName\":\"%s\",\"oldPhone\":\"%s\",\"newPhone\":\"%s\"}",
                        oldName, saved.getName(), oldPhone, saved.getPhone()));

        return toResponse(saved);
    }

    // ── Activate / Suspend ──────────────────────────────────────────────────

    @Transactional
    public AdminDriverResponse setActive(UUID id, boolean active, String reason, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        if (driver.getAccountStatus() == DriverAccountStatus.PENDING_SETUP) {
            throw AppException.badRequest("Cannot toggle status of a driver with pending setup");
        }
        DriverAccountStatus previous = driver.getAccountStatus();
        DriverAccountStatus next = active ? DriverAccountStatus.ACTIVE : DriverAccountStatus.SUSPENDED;
        driver.setAccountStatus(next);
        if (active) {
            driver.setSuspendedReason(null);
        } else {
            driver.setSuspendedReason(reason);
        }
        Driver saved = driverRepo.save(driver);

        String action = active ? "DRIVER_ACTIVATED" : "DRIVER_SUSPENDED";
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
            throw AppException.badRequest(
                    "Only pending invites can be cancelled — use Suspend for active drivers");
        }

        inviteTokenRepo.deleteByDriverId(driver.getId());
        driverRepo.delete(driver);

        auditLogService.log(
                "DRIVER_INVITE_CANCELLED",
                id,
                actorName(actor), actorRole(actor),
                String.format("{\"name\":\"%s\",\"phone\":\"%s\",\"reason\":\"%s\"}",
                        driver.getName(), driver.getPhone(), reason != null ? reason : ""));
    }

    // ── Import ──────────────────────────────────────────────────────────────

    @Transactional
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
                String pass  = cols[2].trim();
                if (name.isBlank() || phone.isBlank() || pass.isBlank()) { skipped++; continue; }
                if (driverRepo.existsByPhone(phone)) { skipped++; continue; }
                Driver driver = Driver.builder()
                        .name(name).phone(phone)
                        .passwordHash(passwordEncoder.encode(pass))
                        .accountStatus(DriverAccountStatus.ACTIVE).build();
                Driver saved = driverRepo.save(driver);
                created.add(toResponse(saved));
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

    // ── Reset password ──────────────────────────────────────────────────────

    @Transactional
    public void resetPassword(UUID id, String newPassword, UserPrincipal actor) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setPasswordHash(passwordEncoder.encode(newPassword));
        driverRepo.save(driver);

        auditLogService.log(
                "DRIVER_PASSWORD_RESET",
                driver.getId(),
                actorName(actor), actorRole(actor),
                "{\"by\":\"admin\"}");
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
                .active(d.getAccountStatus() == DriverAccountStatus.ACTIVE)
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
}
