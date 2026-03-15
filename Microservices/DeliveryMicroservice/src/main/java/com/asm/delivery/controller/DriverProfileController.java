package com.asm.delivery.controller;

import com.asm.delivery.dto.request.ChangePasswordRequest;
import com.asm.delivery.dto.request.LocationUpdateRequest;
import com.asm.delivery.dto.request.UpdateAvailabilityRequest;
import com.asm.delivery.dto.request.UpdateProfileRequest;
import com.asm.delivery.dto.response.*;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.DriverDeliveryService;
import com.asm.delivery.service.DriverProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Driver Profile", description = "Driver profile, availability, location and stats")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DriverProfileController {

    private final DriverProfileService  profileService;
    private final DriverDeliveryService deliveryService;

    @GetMapping("/api/driver/profile")
    @Operation(summary = "Get driver profile")
    public ResponseEntity<DriverProfileResponse> getProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(profileService.getProfile(UUID.fromString(principal.getUserId())));
    }

    @PutMapping("/api/driver/profile")
    @Operation(summary = "Update driver display name")
    public ResponseEntity<DriverProfileResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(profileService.updateProfile(UUID.fromString(principal.getUserId()), req.getName(), req.getCity()));
    }

    @PutMapping("/api/driver/password")
    @Operation(summary = "Change driver password")
    public ResponseEntity<MessageResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        profileService.changePassword(UUID.fromString(principal.getUserId()),
                req.getCurrentPassword(), req.getNewPassword());
        return ResponseEntity.ok(new MessageResponse("Password changed successfully"));
    }

    @PutMapping("/api/driver/availability")
    @Operation(summary = "Update driver availability status")
    public ResponseEntity<DriverProfileResponse> updateAvailability(
            @Valid @RequestBody UpdateAvailabilityRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(profileService.updateAvailability(
                UUID.fromString(principal.getUserId()), req.getAvailable()));
    }

    @GetMapping("/api/driver/history")
    @Operation(summary = "Get driver's completed/failed/cancelled deliveries")
    public ResponseEntity<List<DriverDeliveryResponse>> getHistory(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(profileService.getHistory(UUID.fromString(principal.getUserId())));
    }

    @GetMapping("/api/driver/stats")
    @Operation(summary = "Get driver statistics")
    public ResponseEntity<DriverStatsResponse> getStats(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(profileService.getStats(UUID.fromString(principal.getUserId())));
    }

    @PostMapping("/api/driver/location")
    @Operation(summary = "Update driver GPS location (also stores tracking point for active delivery)")
    public ResponseEntity<MessageResponse> updateLocation(
            @Valid @RequestBody LocationUpdateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        deliveryService.updateLocation(UUID.fromString(principal.getUserId()), req.getLat(), req.getLng());
        return ResponseEntity.ok(new MessageResponse("Location updated"));
    }
}
