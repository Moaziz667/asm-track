package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.security.UserPrincipal;
import com.asm.appbackend.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Current-user profile, read live from the DB (not the JWT). The admin UI sources the displayed
 * identity from here so a name/email change is reflected immediately — without needing a new token
 * (re-login). Deliberately separate from {@link AdminUserController} so it isn't behind that
 * controller's {@code perm:user:manage} guard: any authenticated admin/dispatcher/manager reads
 * only their own record (the gateway allows {@code /api/admin/me} for any authenticated user).
 */
@RestController
@RequestMapping("/api/v1/admin/me")
@RequiredArgsConstructor
@Tag(name = "Admin · Me", description = "The signed-in back-office user's own profile. Available to any "
        + "authenticated admin/dispatcher/manager — no special permission required, and only ever exposes "
        + "the caller's own record.")
@SecurityRequirement(name = "bearerAuth")
public class MeController {

    private final AdminUserService adminUserService;

    @GetMapping
    @Operation(summary = "Get my profile",
            description = "Returns the caller's own profile, with the display name read live from Keycloak so a "
                    + "self-edit shows immediately without re-login (the token's name claim is a login-time snapshot).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's profile"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    })
    public ResponseEntity<AdminUserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        // principal.getName() is the app user id (= admin_users.id), injected by JwtAuthConverter.
        // Reads the display name LIVE from Keycloak (the master) so a self-edit in "Mon compte" shows
        // without a re-login — the token's name claim is a stale login-time snapshot.
        return ResponseEntity.ok(adminUserService.getMeLive(UUID.fromString(principal.getName())));
    }
}
