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

        String token = jwtService.generateAdminToken(user.getId().toString(), user.getRole(), user.getName());

        return AdminLoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresInMs(jwtService.getAccessExpiryMs())
                .user(toResponse(user))
                .build();
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
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
