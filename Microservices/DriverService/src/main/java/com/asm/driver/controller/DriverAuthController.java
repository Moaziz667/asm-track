package com.asm.driver.controller;

import com.asm.driver.dto.request.LoginRequest;
import com.asm.driver.dto.request.RefreshTokenRequest;
import com.asm.driver.dto.request.RegisterRequest;
import com.asm.driver.dto.response.AuthResponse;
import com.asm.driver.service.DriverAuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/driver")
@RequiredArgsConstructor
@Tag(name = "Driver Auth", description = "Public auth endpoints for Driver App")
public class DriverAuthController {

    private final DriverAuthService authService;

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
}
