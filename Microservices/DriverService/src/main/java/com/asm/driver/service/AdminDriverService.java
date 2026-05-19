package com.asm.driver.service;

import com.asm.driver.dto.response.AdminDriverResponse;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverStats;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.repository.DriverStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminDriverService {

    private final DriverRepository driverRepo;
    private final DriverStatsRepository statsRepo;
    private final PasswordEncoder passwordEncoder;

    public List<AdminDriverResponse> listAll() {
        return driverRepo.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public AdminDriverResponse getById(UUID id) {
        return driverRepo.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
    }

    @Transactional
    public AdminDriverResponse create(String name, String phone, String password) {
        if (driverRepo.existsByPhone(phone)) {
            throw AppException.conflict("Phone already registered");
        }
        Driver driver = Driver.builder()
                .name(name)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(password))
                .active(true)
                .build();
        return toResponse(driverRepo.save(driver));
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
                .build();
    }
}
