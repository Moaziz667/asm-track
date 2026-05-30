package com.asm.driver.service;

import com.asm.driver.dto.response.AdminDriverResponse;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverInviteToken;
import com.asm.driver.entity.DriverStats;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverInviteTokenRepository;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.repository.DriverStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AdminDriverService {

    private final DriverRepository driverRepo;
    private final DriverStatsRepository statsRepo;
    private final DriverInviteTokenRepository inviteTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public List<AdminDriverResponse> listAll() {
        List<Driver> drivers = driverRepo.findAll();
        return drivers.stream().map(this::toResponse).toList();
    }

    public AdminDriverResponse getById(UUID id) {
        return driverRepo.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
    }

    @Transactional
    public AdminDriverResponse invite(String name, String phone, String email) {
        if (driverRepo.existsByPhone(phone)) {
            throw AppException.conflict("Phone already registered");
        }
        // Create account inactive — activated when driver completes setup
        Driver driver = Driver.builder()
                .name(name)
                .phone(phone)
                .email(email)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString())) // unusable placeholder
                
                .active(false)
                .build();
        driver = driverRepo.save(driver);

        // Generate invite token (48h TTL)
        UUID token = UUID.randomUUID();
        inviteTokenRepo.save(DriverInviteToken.builder()
                .driverId(driver.getId())
                .token(token)
                .expiresAt(LocalDateTime.now().plusHours(48))
                .build());

        emailService.sendDriverInvite(email, name, token.toString());
        return toResponse(driver);
    }

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
        driver.setActive(true);
        driverRepo.save(driver);

        invite.setUsed(true);
        inviteTokenRepo.save(invite);

        return toResponse(driver);
    }

    public String validateInviteToken(UUID token) {
        DriverInviteToken invite = inviteTokenRepo.findByToken(token)
                .orElseThrow(() -> AppException.notFound("Invalid invite token"));
        if (invite.isUsed())
            throw AppException.badRequest("Invite already used");
        if (invite.getExpiresAt().isBefore(LocalDateTime.now()))
            throw AppException.badRequest("Invite expired");
        Driver driver = driverRepo.findById(invite.getDriverId())
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        return driver.getName();
    }

    @Transactional
    public AdminDriverResponse update(UUID id, String name, String phone) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        if (name  != null && !name.isBlank())  driver.setName(name.trim());
        if (phone != null && !phone.isBlank()) {
            if (!phone.equals(driver.getPhone()) && driverRepo.existsByPhone(phone)) {
                throw AppException.conflict("Phone already in use");
            }
            driver.setPhone(phone.trim());
        }
        return toResponse(driverRepo.save(driver));
    }

    @Transactional
    public AdminDriverResponse setActive(UUID id, boolean active) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setActive(active);
        return toResponse(driverRepo.save(driver));
    }

    @Transactional
    public List<AdminDriverResponse> importCsv(MultipartFile file) {
        List<AdminDriverResponse> created = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // skip header
                String[] cols = line.split(",", -1);
                if (cols.length < 3) continue;
                String name  = cols[0].trim();
                String phone = cols[1].trim();
                String pass  = cols[2].trim();
                if (name.isBlank() || phone.isBlank() || pass.isBlank()) continue;
                if (driverRepo.existsByPhone(phone)) continue; // skip duplicates silently
                Driver driver = Driver.builder()
                        .name(name).phone(phone)
                        .passwordHash(passwordEncoder.encode(pass))
                        .active(true).build();
                created.add(toResponse(driverRepo.save(driver)));
            }
        } catch (Exception e) {
            throw AppException.badRequest("CSV parsing failed: " + e.getMessage());
        }
        return created;
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        Driver driver = driverRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setPasswordHash(passwordEncoder.encode(newPassword));
        driverRepo.save(driver);
    }

    private AdminDriverResponse toResponse(Driver d) {
        DriverStats stats = statsRepo.findByDriverId(d.getId()).orElse(null);
        return AdminDriverResponse.builder()
                .id(d.getId().toString())
                .name(d.getName())
                .phone(d.getPhone())
                
                .active(d.getActive())
                .currentLat(d.getCurrentLat())
                .currentLng(d.getCurrentLng())
                .lastLocationAt(d.getLastLocationAt())
                .createdAt(d.getCreatedAt())
                .totalDeliveries(stats != null ? stats.getTotalDeliveries() : 0)
                .delivered(stats != null ? stats.getDelivered() : 0)
                .failed(stats != null ? stats.getFailed() : 0)
                .onlineStatus(d.getOnlineStatus() != null ? d.getOnlineStatus().name() : "OFFLINE")
                .email(d.getEmail())
                .build();
    }
}
