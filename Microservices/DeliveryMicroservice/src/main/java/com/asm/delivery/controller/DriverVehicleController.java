package com.asm.delivery.controller;

import com.asm.delivery.dto.request.VehicleInspectionRequest;
import com.asm.delivery.dto.response.VehicleInspectionResponse;
import com.asm.delivery.dto.response.VehicleResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.VehicleInspectionService;
import com.asm.delivery.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/driver/vehicles")
@Tag(name = "Driver Vehicles", description = "Vehicle management for drivers")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DriverVehicleController {

    private final VehicleInspectionService inspectionService;
    private final VehicleService vehicleService;

    @PostMapping("/inspection")
    @Operation(summary = "Submit a pre-route vehicle inspection")
    public ResponseEntity<VehicleInspectionResponse> submitInspection(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody VehicleInspectionRequest request) {
        return ResponseEntity.ok(inspectionService.submitInspection(UUID.fromString(principal.getUserId()), request));
    }

    @GetMapping("/my-vehicle")
    @Operation(summary = "Get the vehicle assigned to the current driver")
    public ResponseEntity<VehicleResponse> getMyVehicle(@AuthenticationPrincipal UserPrincipal principal) {
        // This assumes there's a method in VehicleService to find by driverId
        // Or we can just use the principal ID to search.
        return ResponseEntity.ok(vehicleService.getByDriver(UUID.fromString(principal.getUserId())));
    }
}
