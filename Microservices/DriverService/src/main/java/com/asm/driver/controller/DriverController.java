package com.asm.driver.controller;

import com.asm.driver.dto.request.LocationRequest;
import com.asm.driver.dto.request.PasswordUpdateRequest;
import com.asm.driver.dto.response.DriverProfileResponse;
import com.asm.driver.dto.response.HistoryResponse;
import com.asm.driver.dto.response.StatsResponse;
import com.asm.driver.entity.DriverOnlineStatus;
import com.asm.driver.security.UserPrincipal;
import com.asm.driver.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/v1/driver")
@RequiredArgsConstructor
@Tag(name = "Driver Operations", description = "Endpoints for Driver App (Requires DRIVER role)")
@SecurityRequirement(name = "bearerAuth")
public class DriverController {

    private final DriverService driverService;

    @GetMapping("/profile")
    @Operation(summary = "Get my driver profile", description = "Returns the signed-in driver's own profile.")
    public ResponseEntity<DriverProfileResponse> getProfile(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(
                driverService.getProfile(UUID.fromString(user.getUserId()), user.getDisplayName()));
    }

    /** Driver uploads/replaces their own profile photo (multipart "file"). Re-encoded + stored server-side. */
    @PostMapping(value = "/me/photo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload my profile photo",
            description = "Uploads/replaces the driver's own photo (multipart field 'file'); re-encoded and stored server-side.")
    public ResponseEntity<?> uploadPhoto(@AuthenticationPrincipal UserPrincipal user,
                                         @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        try {
            return ResponseEntity.ok(
                    driverService.uploadPhoto(UUID.fromString(user.getUserId()), file.getBytes(), user.getDisplayName()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNREADABLE_UPLOAD"));
        }
    }

    // Driver name is Keycloak-mastered (self-service in the account console) → no app-side name edit.

    @PostMapping("/location")
    @Operation(summary = "Report my current location", description = "Pushes the driver's live GPS position (used for tracking).")
    public ResponseEntity<Map<String, String>> updateLocation(@AuthenticationPrincipal UserPrincipal user,
                                                              @Valid @RequestBody LocationRequest req) {
        driverService.updateLocation(UUID.fromString(user.getUserId()), req);
        return ResponseEntity.ok(Map.of("message", "Location updated"));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get my delivery stats", description = "Returns the driver's own performance counters.")
    public ResponseEntity<StatsResponse> getStats(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(driverService.getStats(UUID.fromString(user.getUserId())));
    }

    @GetMapping("/history")
    @Operation(summary = "Get my delivery history", description = "Paginated history of the driver's past deliveries.")
    public ResponseEntity<HistoryResponse> getHistory(
            @AuthenticationPrincipal UserPrincipal user,
            @org.springframework.data.web.PageableDefault(size = 20) org.springframework.data.domain.Pageable pageable) {
        return ResponseEntity.ok(driverService.getHistory(UUID.fromString(user.getUserId()), pageable));
    }

    @PutMapping("/fcm-token")
    @Operation(summary = "Register my push token", description = "Stores the device's FCM token for push notifications.")
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
    @Operation(summary = "Clear my push token", description = "Removes the stored FCM token (e.g. on logout).")
    public ResponseEntity<Map<String, String>> clearFcmToken(@AuthenticationPrincipal UserPrincipal user) {
        driverService.updateFcmToken(UUID.fromString(user.getUserId()), null);
        return ResponseEntity.ok(Map.of("message", "FCM token cleared"));
    }

    @PostMapping("/duty-status")
    @Operation(summary = "Set my on/off-duty status", description = "Toggles whether the driver is on duty and dispatchable.")
    public ResponseEntity<Map<String, Object>> toggleDuty(@AuthenticationPrincipal UserPrincipal user,
                                                          @RequestParam boolean onDuty) {
        driverService.toggleDuty(UUID.fromString(user.getUserId()), onDuty);
        return ResponseEntity.ok(Map.of("onDuty", onDuty, "message", "Duty status updated"));
    }

    @PatchMapping("/availability")
    @Operation(summary = "Set my availability", description = "Sets the driver's online status: ONLINE, ON_BREAK or OFFLINE.")
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
