package com.asm.driver.service;

import com.asm.driver.dto.response.AuthResponse;
import com.asm.driver.dto.response.DriverInfo;
import com.asm.driver.entity.Driver;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DriverAuthService {

    private final DriverRepository driverRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public Map<String, String> register(String name, String phone, String password) {
        if (driverRepo.existsByPhone(phone)) {
            throw AppException.conflict("Phone already registered");
        }

        Driver driver = Driver.builder()
                .name(name)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(password))
                .build();
        driverRepo.save(driver);
        return Map.of("message", "Registration successful", "driverId", driver.getId().toString());
    }

    public AuthResponse login(String phone, String password) {
        Driver driver = driverRepo.findByPhone(phone)
                .orElseThrow(() -> AppException.unauthorized("Invalid credentials"));

        if (!passwordEncoder.matches(password, driver.getPasswordHash())) {
            throw AppException.unauthorized("Invalid credentials");
        }
        if (!driver.getActive()) {
            throw AppException.unauthorized("Account disabled");
        }

        return buildResponse(driver);
    }

    public Map<String, String> refreshToken(String refreshToken) {
        if (!jwtService.isValid(refreshToken)) {
            throw AppException.unauthorized("Invalid refresh token");
        }
        var claims = jwtService.parseToken(refreshToken);
        if (!"refresh".equals(claims.get("type"))) {
            throw AppException.unauthorized("Invalid token type");
        }
        Driver driver = driverRepo.findById(UUID.fromString(claims.getSubject()))
                .orElseThrow(() -> AppException.unauthorized("Driver not found"));
                
        String newAccess = jwtService.generateAccessToken(
                driver.getId().toString(), "DRIVER",
                Map.of(
                    "name", driver.getName(),
                    "phone", driver.getPhone()
                )
        );
        return Map.of("token", newAccess);
    }

    @Transactional
    public void changePassword(UUID driverId, String oldPassword, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw AppException.badRequest("New passwords do not match");
        }

        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        if (!passwordEncoder.matches(oldPassword, driver.getPasswordHash())) {
            throw AppException.badRequest("Le mot de passe actuel est incorrect");
        }

        driver.setPasswordHash(passwordEncoder.encode(newPassword));
        driverRepo.save(driver);
    }

    private AuthResponse buildResponse(Driver driver) {
        String driverId = driver.getId().toString();
        String accessToken = jwtService.generateAccessToken(driverId, "DRIVER",
                Map.of(
                    "name", driver.getName(),
                    "phone", driver.getPhone()
                ));
        String refreshToken = jwtService.generateRefreshToken(driverId, "DRIVER");

        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .driver(DriverInfo.builder()
                        .id(driverId)
                        .name(driver.getName())
                        .phone(driver.getPhone())
                        .build())
                .build();
    }
}
