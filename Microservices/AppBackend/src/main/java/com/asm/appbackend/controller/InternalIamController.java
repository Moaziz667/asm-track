package com.asm.appbackend.controller;

import com.asm.appbackend.client.KeycloakAdminClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Internal · IAM", description = "Service-to-service (SERVICE role) synchronous Keycloak operations "
        + "that can't be eventual — e.g. setting a driver's password at onboarding so they can log in at once. "
        + "AppBackend is the sole Keycloak owner; other services call these.")
@SecurityRequirement(name = "bearerAuth")
public class InternalIamController {

    private final KeycloakAdminClient kc;

    @PostMapping("/{appUserId}/password")
    @Operation(summary = "[internal] Set a user's password",
            description = "Sets a permanent password for the user in Keycloak. Body: {\"password\": \"…\"}. Used at "
                    + "driver onboarding so login works immediately.")
    @ApiResponse(responseCode = "204", description = "Password set")
    public ResponseEntity<Void> setPassword(@PathVariable String appUserId, @RequestBody Map<String, String> body) {
        kc.resetPassword(appUserId, body.get("password"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{appUserId}/enabled")
    @Operation(summary = "[internal] Enable or disable a user",
            description = "Enables/disables the user's Keycloak account. Body: {\"enabled\": true|false}.")
    @ApiResponse(responseCode = "204", description = "Account state updated")
    public ResponseEntity<Void> setEnabled(@PathVariable String appUserId, @RequestBody Map<String, Boolean> body) {
        kc.setUserEnabled(appUserId, Boolean.TRUE.equals(body.get("enabled")));
        return ResponseEntity.noContent().build();
    }
}
