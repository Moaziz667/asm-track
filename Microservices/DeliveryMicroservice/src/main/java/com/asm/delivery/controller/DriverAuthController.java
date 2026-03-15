package com.asm.delivery.controller;

import com.asm.delivery.dto.request.DriverLoginRequest;
import com.asm.delivery.dto.request.DriverRegisterRequest;
import com.asm.delivery.dto.request.RefreshTokenRequest;
import com.asm.delivery.dto.response.AuthResponse;
import com.asm.delivery.service.DriverAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/driver")
@Tag(name = "Driver Auth", description = "Driver registration, login and token refresh")
@RequiredArgsConstructor
public class DriverAuthController {

    private final DriverAuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new driver")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody DriverRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(req.getName(), req.getPhone(), req.getPassword(), req.getCity()));
    }

    @PostMapping("/login")
    @Operation(summary = "Driver login — returns access + refresh JWT")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody DriverLoginRequest req) {
        return ResponseEntity.ok(authService.login(req.getPhone(), req.getPassword()));
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Refresh driver access token")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(authService.refresh(req.getRefreshToken()));
    }
}
