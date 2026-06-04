package com.asm.driver.controller;

import com.asm.driver.dto.response.AdminDriverResponse;
import com.asm.driver.service.AdminDriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth/driver/setup")
@RequiredArgsConstructor
@Tag(name = "Driver Activation", description = "Endpoints for driver account activation and password setup")
public class DriverAuthController {

    private final AdminDriverService adminDriverService;

    @GetMapping("/validate")
    @Operation(summary = "Validate invitation token and retrieve driver name")
    public ResponseEntity<Map<String, String>> validateToken(@RequestParam UUID token) {
        String name = adminDriverService.validateInviteToken(token);
        return ResponseEntity.ok(Map.of("name", name));
    }

    @PostMapping
    @Operation(summary = "Activate account and set password")
    public ResponseEntity<AdminDriverResponse> setupAccount(@Valid @RequestBody SetupAccountRequest req) {
        AdminDriverResponse response = adminDriverService.setupAccount(req.token(), req.password());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/resend")
    @Operation(summary = "Resend activation code")
    public ResponseEntity<Map<String, Object>> resendCode(@RequestParam String phone) {
        Map<String, Object> result = adminDriverService.resendActivationCode(phone);
        return ResponseEntity.ok(result);
    }

    public record SetupAccountRequest(UUID token, @NotBlank String password) {}
}
