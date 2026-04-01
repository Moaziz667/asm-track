package com.asm.delivery.controller;

import com.asm.delivery.dto.request.AssignVehicleRequest;
import com.asm.delivery.dto.request.CreateVehicleRequest;
import com.asm.delivery.dto.request.UpdateVehicleRequest;
import com.asm.delivery.dto.response.VehicleResponse;
import com.asm.delivery.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/vehicles")
@Tag(name = "Admin Vehicles", description = "Vehicle management for route planning")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminVehicleController {

    private final VehicleService vehicleService;

    @GetMapping
    @Operation(summary = "List vehicles")
    public ResponseEntity<List<VehicleResponse>> list() {
        return ResponseEntity.ok(vehicleService.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get vehicle")
    public ResponseEntity<VehicleResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(vehicleService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create vehicle")
    public ResponseEntity<VehicleResponse> create(@Valid @RequestBody CreateVehicleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vehicleService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update vehicle")
    public ResponseEntity<VehicleResponse> update(@PathVariable UUID id, @RequestBody UpdateVehicleRequest request) {
        return ResponseEntity.ok(vehicleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete vehicle")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        vehicleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/assign")
    @Operation(summary = "Assign vehicle to driver")
    public ResponseEntity<VehicleResponse> assign(@PathVariable UUID id, @Valid @RequestBody AssignVehicleRequest request) {
        return ResponseEntity.ok(vehicleService.assign(id, request));
    }
}
