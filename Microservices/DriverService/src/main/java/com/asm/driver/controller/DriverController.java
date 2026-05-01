package com.asm.driver.controller;

import com.asm.driver.dto.request.LocationRequest;
import com.asm.driver.dto.request.PasswordUpdateRequest;
import com.asm.driver.dto.request.ProfileUpdateRequest;
import com.asm.driver.dto.response.DriverProfileResponse;
import com.asm.driver.dto.response.HistoryResponse;
import com.asm.driver.dto.response.StatsResponse;
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

    @PutMapping("/password")
    public ResponseEntity<Map<String, String>> updatePassword(@AuthenticationPrincipal UserPrincipal user,
                                                              @Valid @RequestBody PasswordUpdateRequest req) {
        driverService.updatePassword(UUID.fromString(user.getUserId()), req.getCurrentPassword(), req.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated"));
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

    @PostMapping("/duty-status")
    public ResponseEntity<Map<String, Object>> toggleDuty(@AuthenticationPrincipal UserPrincipal user,
                                                          @RequestParam boolean onDuty) {
        driverService.toggleDuty(UUID.fromString(user.getUserId()), onDuty);
        return ResponseEntity.ok(Map.of("onDuty", onDuty, "message", "Duty status updated"));
    }
}
