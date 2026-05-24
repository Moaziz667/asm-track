package com.asm.driver.controller;

import com.asm.driver.dto.request.LoginRequest;
import com.asm.driver.dto.request.RefreshTokenRequest;
import com.asm.driver.dto.request.RegisterRequest;
import com.asm.driver.dto.request.ChangePasswordRequest;
import com.asm.driver.dto.response.AuthResponse;
import com.asm.driver.security.UserPrincipal;
import com.asm.driver.service.AdminDriverService;
import com.asm.driver.service.DriverAuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth/driver")
@RequiredArgsConstructor
@Tag(name = "Driver Auth", description = "Public auth endpoints for Driver App")
public class DriverAuthController {

    private final DriverAuthService authService;
    private final AdminDriverService adminDriverService;

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req.getName(), req.getPhone(), req.getPassword()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req.getPhone(), req.getPassword()));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, String>> refreshToken(@Valid @RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(authService.refreshToken(req.getRefreshToken()));
    }

    @GetMapping("/setup/validate")
    public ResponseEntity<Map<String, String>> validateInvite(@RequestParam UUID token) {
        String name = adminDriverService.validateInviteToken(token);
        return ResponseEntity.ok(Map.of("name", name));
    }

    @PostMapping("/setup")
    public ResponseEntity<Map<String, String>> setupAccount(@RequestBody SetupAccountRequest req) {
        adminDriverService.setupAccount(req.token(), req.password());
        return ResponseEntity.ok(Map.of("message", "Account activated. You can now log in."));
    }

    public record SetupAccountRequest(UUID token, @NotBlank @Size(min = 6) String password) {}

    @PutMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @org.springframework.security.core.annotation.AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(UUID.fromString(principal.getUserId()), req.getOldPassword(), req.getNewPassword(), req.getConfirmPassword());
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }
}
