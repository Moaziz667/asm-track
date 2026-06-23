package com.asm.appbackend.controller;

import com.asm.appbackend.client.KeycloakAdminClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Synchronous IAM operations that can't be eventual — namely setting a driver's password at
 * onboarding so they can log in immediately. DriverService (which no longer talks to Keycloak)
 * calls these; AppBackend, the sole Keycloak owner, performs them. SERVICE-guarded via
 * {@code /internal/**} in SecurityConfig.
 */
@RestController
@RequestMapping("/internal/iam")
@RequiredArgsConstructor
public class InternalIamController {

    private final KeycloakAdminClient kc;

    @PostMapping("/{appUserId}/password")
    public ResponseEntity<Void> setPassword(@PathVariable String appUserId, @RequestBody Map<String, String> body) {
        kc.resetPassword(appUserId, body.get("password"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{appUserId}/enabled")
    public ResponseEntity<Void> setEnabled(@PathVariable String appUserId, @RequestBody Map<String, Boolean> body) {
        kc.setUserEnabled(appUserId, Boolean.TRUE.equals(body.get("enabled")));
        return ResponseEntity.noContent().build();
    }
}
