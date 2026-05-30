package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.dto.admin.CreateAdminUserRequest;
import com.asm.appbackend.security.UserPrincipal;
import com.asm.appbackend.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final RestTemplate restTemplate;

    @Value("${delivery.service.url:http://delivery-service:8082}")
    private String deliveryServiceUrl;

    @Value("${auth.server.url:http://auth-server:8089}")
    private String authServerUrl;

    @Value("${auth.client.id:app-backend}")
    private String clientId;

    @Value("${auth.client.secret}")
    private String clientSecret;

    private volatile String cachedServiceToken;
    private volatile long tokenExpiresAt;

    private synchronized String getServiceToken() {
        if (cachedServiceToken != null && System.currentTimeMillis() < tokenExpiresAt - 10_000) {
            return cachedServiceToken;
        }
        var params = new LinkedMultiValueMap<String, String>();
        params.add("grant_type",    "client_credentials");
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);
        var headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        var response = restTemplate.exchange(
                authServerUrl + "/oauth2/token",
                HttpMethod.POST,
                new HttpEntity<>(params, headers),
                Map.class);
        var body = response.getBody();
        cachedServiceToken = (String) body.get("access_token");
        int expiresIn = body.get("expires_in") instanceof Number n ? n.intValue() : 300;
        tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        return cachedServiceToken;
    }

    @PostMapping
    public ResponseEntity<AdminUserResponse> createUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateAdminUserRequest req) {
        AdminUserResponse response = adminUserService.createUser(req);
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            String actorName = (auth != null && auth.getName() != null) ? auth.getName() : "Admin";
            String auditUrl = UriComponentsBuilder
                    .fromHttpUrl(deliveryServiceUrl + "/internal/audit")
                    .queryParam("action", "CREATE_ADMIN_USER")
                    .queryParam("actorName", actorName)
                    .queryParam("actorRole", "ADMIN")
                    .queryParam("resourceId", response.id())
                    .queryParam("details", "Created " + req.role() + " account: " + req.name() + " (" + req.email() + ")")
                    .toUriString();
            var headers = new HttpHeaders();
            headers.setBearerAuth(getServiceToken());
            restTemplate.postForEntity(auditUrl, new HttpEntity<>(null, headers), Void.class);
        } catch (Exception e) {
            log.warn("Failed to push audit for user creation: {}", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> listUsers(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(adminUserService.listUsers());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminUserResponse> setStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body) {
        Boolean active = body.get("active");
        if (active == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(adminUserService.setActive(id, active));
    }
}
