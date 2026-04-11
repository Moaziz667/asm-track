package com.asm.delivery.controller;

import com.asm.delivery.dto.request.ZoneRequest;
import com.asm.delivery.dto.response.ZoneResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.ZoneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/zones")
@Tag(name = "Zones", description = "Delivery zone management")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class ZoneController {

    private final ZoneService zoneService;

    @GetMapping
    @Operation(summary = "List all zones")
    public ResponseEntity<List<ZoneResponse>> list() {
        return ResponseEntity.ok(zoneService.list());
    }

    @GetMapping("/active")
    @Operation(summary = "List active zones ordered by name")
    public ResponseEntity<List<ZoneResponse>> listActive() {
        return ResponseEntity.ok(zoneService.listActive());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get zone by ID")
    public ResponseEntity<ZoneResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(zoneService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create zone")
    public ResponseEntity<ZoneResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ZoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(zoneService.create(principal, request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update zone")
    public ResponseEntity<ZoneResponse> update(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ZoneRequest request) {
        return ResponseEntity.ok(zoneService.update(id, principal, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete zone (sets isActive = false)")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        zoneService.delete(id, principal);
        return ResponseEntity.noContent().build();
    }
}
