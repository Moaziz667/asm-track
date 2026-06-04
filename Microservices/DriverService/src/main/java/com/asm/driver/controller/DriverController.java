package com.asm.driver.controller;

import com.asm.driver.dto.request.LocationRequest;
import com.asm.driver.dto.request.PasswordUpdateRequest;
import com.asm.driver.dto.request.ProfileUpdateRequest;
import com.asm.driver.dto.response.DriverProfileResponse;
import com.asm.driver.dto.response.HistoryResponse;
import com.asm.driver.dto.response.StatsResponse;
import com.asm.driver.entity.DriverOnlineStatus;
import com.asm.driver.security.UserPrincipal;
import com.asm.driver.service.DriverService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
@Tag(name = "Driver Operations", description = "Endpoints for Driver App (Requires DRIVER role)")
@SecurityRequirement(name = "bearerAuth")
public class DriverController {

    private final DriverService driverService;

    @GetMapping("/profile")
    public ResponseEntity<DriverProfileResponse> getProfile(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(driverService.getProfile(UUID.fromString(user.getUserId())));
    }

    @PutMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(@AuthenticationPrincipal UserPrincipal user,
                                                             @Valid @RequestBody ProfileUpdateRequest req) {
        driverService.updateProfile(UUID.fromString(user.getUserId()), req.getName());
        return ResponseEntity.ok(Map.of("message", "Profile updated"));
    }



    @PostMapping("/location")
    public ResponseEntity<Map<String, String>> updateLocation(@AuthenticationPrincipal UserPrincipal user,
                                                              @Valid @RequestBody LocationRequest req) {
        driverService.updateLocation(UUID.fromString(user.getUserId()), req);
        return ResponseEntity.ok(Map.of("message", "Location updated"));
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> getStats(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(driverService.getStats(UUID.fromString(user.getUserId())));
    }

    @GetMapping("/history")
    public ResponseEntity<HistoryResponse> getHistory(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(driverService.getHistory(UUID.fromString(user.getUserId())));
    }

    @PutMapping("/fcm-token")
    public ResponseEntity<Map<String, String>> updateFcmToken(@AuthenticationPrincipal UserPrincipal user,
                                                              @RequestBody Map<String, String> body) {
        String token = body.getOrDefault("fcmToken", body.get("token"));
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fcmToken is required"));
        }
        driverService.updateFcmToken(UUID.fromString(user.getUserId()), token);
        return ResponseEntity.ok(Map.of("message", "FCM token updated"));
    }

    @DeleteMapping("/fcm-token")
    public ResponseEntity<Map<String, String>> clearFcmToken(@AuthenticationPrincipal UserPrincipal user) {
        driverService.updateFcmToken(UUID.fromString(user.getUserId()), null);
        return ResponseEntity.ok(Map.of("message", "FCM token cleared"));
    }

    @PostMapping("/duty-status")
    public ResponseEntity<Map<String, Object>> toggleDuty(@AuthenticationPrincipal UserPrincipal user,
                                                          @RequestParam boolean onDuty) {
        driverService.toggleDuty(UUID.fromString(user.getUserId()), onDuty);
        return ResponseEntity.ok(Map.of("onDuty", onDuty, "message", "Duty status updated"));
    }

    @PatchMapping("/availability")
    public ResponseEntity<Map<String, String>> updateAvailability(@AuthenticationPrincipal UserPrincipal user,
                                                                  @RequestBody Map<String, String> body) {
        String raw = body.get("status");
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }
        DriverOnlineStatus status;
        try {
            status = DriverOnlineStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status. Use ONLINE, ON_BREAK, or OFFLINE"));
        }
        DriverOnlineStatus result = driverService.updateAvailability(UUID.fromString(user.getUserId()), status);
        return ResponseEntity.ok(Map.of("status", result.name()));
    }
}
