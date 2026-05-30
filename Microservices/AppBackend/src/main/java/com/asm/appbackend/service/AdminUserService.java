package com.asm.appbackend.service;

import com.asm.appbackend.dto.admin.*;
import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.repository.AdminUserRepository;
import com.asm.appbackend.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RestTemplate restTemplate;

    @Value("${auth.server.url}")
    private String authServerUrl;

    @Value("${auth.client.id}")
    private String clientId;

    @Value("${auth.client.secret}")
    private String clientSecret;

    // ── Login — delegates password grant to auth-server ───────────────────────

    @Transactional(readOnly = true)
    public AdminLoginResponse login(AdminLoginRequest req) {
        // Check account exists and is active before calling auth-server
        AdminUser user = adminUserRepo.findByEmail(req.email())
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isActive()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Account is disabled");
        }

        Map<String, Object> tokenResponse = callAuthServerPasswordGrant(req.email(), req.password());

        return AdminLoginResponse.builder()
                .token((String) tokenResponse.get("access_token"))
                .refreshToken((String) tokenResponse.get("refresh_token"))
                .tokenType("Bearer")
                .expiresInMs(((Number) tokenResponse.get("expires_in")).longValue() * 1000)
                .user(toResponse(user))
                .build();
    }

    // ── Refresh — delegates refresh_token grant to auth-server ────────────────

    @Transactional(readOnly = true)
    public AdminLoginResponse refreshToken(String refreshToken) {
        Map<String, Object> tokenResponse = callAuthServerRefreshGrant(refreshToken);

        // Re-load user for the response DTO (subject = userId)
        String userId = jwtService.parseToken((String) tokenResponse.get("access_token")).getSubject();
        AdminUser user = adminUserRepo.findById(UUID.fromString(userId))
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (!user.isActive()) throw new AppException(HttpStatus.FORBIDDEN, "Account is disabled");

        return AdminLoginResponse.builder()
                .token((String) tokenResponse.get("access_token"))
                .refreshToken((String) tokenResponse.get("refresh_token"))
                .tokenType("Bearer")
                .expiresInMs(((Number) tokenResponse.get("expires_in")).longValue() * 1000)
                .user(toResponse(user))
                .build();
    }

    // ── User management (unchanged) ───────────────────────────────────────────

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

    @Transactional
    public AdminUserResponse setActive(UUID id, boolean active) {
        AdminUser user = adminUserRepo.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        user.setActive(active);
        return toResponse(adminUserRepo.save(user));
    }

    // ── Auth-server calls ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callAuthServerPasswordGrant(String username, String password) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",    "password");
        params.add("username",      username);
        params.add("password",      password);
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    authServerUrl + "/oauth2/token",
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    Map.class);
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("Auth-server password grant failed for {}: {}", username, e.getMessage());
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callAuthServerRefreshGrant(String refreshToken) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",    "refresh_token");
        params.add("refresh_token", refreshToken);
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    authServerUrl + "/oauth2/token",
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    Map.class);
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
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
