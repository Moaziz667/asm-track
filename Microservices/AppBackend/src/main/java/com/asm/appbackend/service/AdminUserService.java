package com.asm.appbackend.service;

import com.asm.appbackend.dto.admin.*;
import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.repository.AdminUserRepository;
import com.asm.appbackend.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final AdminUserRepository adminUserRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public AdminLoginResponse login(AdminLoginRequest req) {
        AdminUser user = adminUserRepo.findByEmail(req.email())
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isActive()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Account is disabled");
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String companyId = user.getCompanyId() != null ? user.getCompanyId().toString() : null;
        String token = jwtService.generateAdminToken(user.getId().toString(), user.getRole(), user.getName(), companyId);
        String refreshToken = jwtService.generateRefreshToken(user.getId().toString(), user.getRole(), user.getName(), companyId);

        return AdminLoginResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresInMs(jwtService.getAccessExpiryMs())
                .user(toResponse(user))
                .build();
    }

    @Transactional(readOnly = true)
    public AdminLoginResponse refreshToken(String refreshToken) {
        try {
            var claims = jwtService.parseToken(refreshToken);
            if (!"refresh".equals(claims.get("type"))) {
                throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid refresh token type");
            }
            String subject = claims.getSubject();

            AdminUser user = adminUserRepo.findById(java.util.UUID.fromString(subject))
                    .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "User not found"));

            if (!user.isActive()) {
                throw new AppException(HttpStatus.FORBIDDEN, "Account is disabled");
            }

            // Rotate: issue both a new access token AND a new refresh token
            String companyId = user.getCompanyId() != null ? user.getCompanyId().toString() : null;
            String newAccessToken  = jwtService.generateAdminToken(user.getId().toString(), user.getRole(), user.getName(), companyId);
            String newRefreshToken = jwtService.generateRefreshToken(user.getId().toString(), user.getRole(), user.getName(), companyId);

            return AdminLoginResponse.builder()
                    .token(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .tokenType("Bearer")
                    .expiresInMs(jwtService.getAccessExpiryMs())
                    .user(toResponse(user))
                    .build();
        } catch (Exception e) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
    }

    @Transactional
    public AdminUserResponse createUser(CreateAdminUserRequest req) {
        if (adminUserRepo.existsByEmail(req.email())) {
            throw new AppException(HttpStatus.CONFLICT, "Email already in use");
        }

        AdminUser user = AdminUser.builder()
                .name(req.name())
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(req.role())
                .companyId(req.companyId())
                .active(true)
                .build();

        adminUserRepo.save(user);
        log.info("Admin user created: email={} role={}", req.email(), req.role());
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return adminUserRepo.findAll().stream().map(this::toResponse).toList();
    }

    private AdminUserResponse toResponse(AdminUser user) {
        return AdminUserResponse.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .companyId(user.getCompanyId())
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
