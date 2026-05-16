package com.asm.driver.service;

import com.asm.driver.dto.response.AuthResponse;
import com.asm.driver.dto.response.DriverInfo;
import com.asm.driver.entity.Driver;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.security.JwtService;
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

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverAuthService {

    private final DriverRepository driverRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RestTemplate restTemplate;

    @Value("${auth.server.url}")
    private String authServerUrl;

    @Value("${auth.client.id}")
    private String clientId;

    @Value("${auth.client.secret}")
    private String clientSecret;

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
        // Verify account exists and is active before calling auth-server
        Driver driver = driverRepo.findByPhone(phone)
                .orElseThrow(() -> AppException.unauthorized("Invalid credentials"));

        if (!driver.getActive()) throw AppException.unauthorized("Account disabled");

        Map<String, Object> tokenResponse = callAuthServerPasswordGrant(phone, password);

        return AuthResponse.builder()
                .token((String) tokenResponse.get("access_token"))
                .refreshToken((String) tokenResponse.get("refresh_token"))
                .driver(DriverInfo.builder()
                        .id(driver.getId().toString())
                        .name(driver.getName())
                        .phone(driver.getPhone())
                        .build())
                .build();
    }

    public Map<String, String> refreshToken(String refreshToken) {
        Map<String, Object> tokenResponse = callAuthServerRefreshGrant(refreshToken);
        return Map.of(
                "token",        (String) tokenResponse.get("access_token"),
                "refreshToken", (String) tokenResponse.get("refresh_token")
        );
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

    // ── Auth-server calls ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callAuthServerPasswordGrant(String phone, String password) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",    "password");
        params.add("username",      phone);
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
            log.warn("Auth-server password grant failed for phone={}: {}", phone, e.getMessage());
            throw AppException.unauthorized("Invalid credentials");
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
            throw AppException.unauthorized("Invalid refresh token");
        }
    }
}
