package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.security.UserPrincipal;
import com.asm.appbackend.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
@RequestMapping("/api/admin/me")
@RequiredArgsConstructor
public class MeController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<AdminUserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        // principal.getName() is the app user id (= admin_users.id), injected by JwtAuthConverter.
        return ResponseEntity.ok(adminUserService.getById(UUID.fromString(principal.getName())));
    }

    /**
     * Login-pull: refresh the name mirror from the token's name claim (the Keycloak-mastered value).
     * The SPA calls this once on sign-in so a name self-edited in the account console reaches the app.
     */
    @PostMapping("/sync")
    public ResponseEntity<AdminUserResponse> sync(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                adminUserService.syncNameFromToken(UUID.fromString(principal.getName()), principal.name()));
    }
}
