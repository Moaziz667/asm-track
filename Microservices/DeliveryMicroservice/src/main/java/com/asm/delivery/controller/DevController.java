package com.asm.delivery.controller;

import com.asm.delivery.dto.request.DevTokenRequest;
import com.asm.delivery.dto.response.DevTokenResponse;
import com.asm.delivery.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * /api/dev/** — helpers for testing without App Backend.
 * Generates a test client JWT using the same JWT_SECRET.
 * Should be disabled or protected in production.
 */
@RestController
@RequestMapping("/api/dev")
@Tag(name = "Dev Utilities", description = "Test helpers — generate client JWT without App Backend")
@RequiredArgsConstructor
public class DevController {

    private final JwtService jwtService;

    @PostMapping("/client-token")
    @Operation(summary = "Generate a test CLIENT JWT for Swagger / Postman testing")
    public ResponseEntity<DevTokenResponse> generateClientToken(@Valid @RequestBody DevTokenRequest req) {
        Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("name", req.getName());
        if (req.getPhone() != null) extra.put("phone", req.getPhone());

        String token = jwtService.generateAccessToken(req.getUserId(), "CLIENT", extra);
        return ResponseEntity.ok(new DevTokenResponse(token, req.getUserId(), "CLIENT"));
    }

    @GetMapping("/ping")
    @Operation(summary = "Health check ping")
    public ResponseEntity<java.util.Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "delivery-service"));
    }
}
