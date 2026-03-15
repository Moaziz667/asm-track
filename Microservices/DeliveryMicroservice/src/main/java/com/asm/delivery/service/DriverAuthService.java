package com.asm.delivery.service;

import com.asm.delivery.dto.response.*;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DriverAuthService {

    private final DriverRepository     driverRepo;
    private final JwtService           jwtService;
    private final PasswordEncoder      passwordEncoder;

    // ── Register ─────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse register(String name, String phone, String password, String city) {
        if (driverRepo.existsByPhone(phone)) {
            throw AppException.conflict("Phone number already registered");
        }

        Driver driver = Driver.builder()
                .name(name)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(password))
                .city(city)
                .available(true)
                .build();

        driver = driverRepo.save(driver);
        return buildAuthResponse(driver);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    public AuthResponse login(String phone, String password) {
        Driver driver = driverRepo.findByPhone(phone)
                .orElseThrow(() -> AppException.unauthorized("Invalid credentials"));

        if (!passwordEncoder.matches(password, driver.getPasswordHash())) {
            throw AppException.unauthorized("Invalid credentials");
        }

        return buildAuthResponse(driver);
    }

    // ── Refresh Token ─────────────────────────────────────────────────────────

    public AuthResponse refresh(String refreshToken) {
        if (!jwtService.isValid(refreshToken)) {
            throw AppException.unauthorized("Invalid or expired refresh token");
        }

        String subject = jwtService.getSubject(refreshToken);
        String role    = jwtService.getRole(refreshToken);

        if (!"DRIVER".equals(role)) {
            throw AppException.unauthorized("Not a driver token");
        }

        Driver driver = driverRepo.findById(UUID.fromString(subject))
                .orElseThrow(() -> AppException.unauthorized("Driver not found"));

        return buildAuthResponse(driver);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(Driver driver) {
        String driverId = driver.getId().toString();

        String accessToken  = jwtService.generateAccessToken(driverId, "DRIVER",
                Map.of("name", driver.getName(), "phone", driver.getPhone()));
        String refreshToken = jwtService.generateRefreshToken(driverId, "DRIVER");

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessExpiryMs() / 1000)
                .driver(AuthResponse.DriverInfo.builder()
                        .id(driverId)
                        .name(driver.getName())
                        .phone(driver.getPhone())
                        .available(driver.getAvailable())
                        .build())
                .build();
    }
}
