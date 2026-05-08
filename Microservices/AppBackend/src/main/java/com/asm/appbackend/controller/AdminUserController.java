package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.dto.admin.CreateAdminUserRequest;
import com.asm.appbackend.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
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

    @Value("${internal.secret:asm-internal-2026}")
    private String internalSecret;

    @PostMapping
    public ResponseEntity<AdminUserResponse> createUser(@Valid @RequestBody CreateAdminUserRequest req) {
        AdminUserResponse response = adminUserService.createUser(req);
        // Fire-and-forget audit to DeliveryMicroservice
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
            restTemplate.postForEntity(auditUrl + "&X-Internal-Secret=" + internalSecret, null, Void.class);
        } catch (Exception e) {
            log.warn("Failed to push audit for user creation: {}", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> listUsers() {
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
