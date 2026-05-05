package com.asm.delivery.controller;

import com.asm.delivery.dto.request.DepotRequest;
import com.asm.delivery.dto.response.DepotResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.DepotService;
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
@RequestMapping("/api/v1/depots")
@Tag(name = "Depots", description = "Depot/warehouse management")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DepotController {

    private final DepotService depotService;

    @GetMapping
    @Operation(summary = "List all depots")
    public ResponseEntity<List<DepotResponse>> list() {
        return ResponseEntity.ok(depotService.list());
    }

    @GetMapping("/active")
    @Operation(summary = "List active depots")
    public ResponseEntity<List<DepotResponse>> listActive() {
        return ResponseEntity.ok(depotService.listActive());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get depot by ID")
    public ResponseEntity<DepotResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(depotService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create depot")
    public ResponseEntity<DepotResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DepotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(depotService.create(principal, request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update depot")
    public ResponseEntity<DepotResponse> update(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DepotRequest request) {
        return ResponseEntity.ok(depotService.update(id, principal, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete depot")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        depotService.delete(id, principal);
        return ResponseEntity.noContent().build();
    }
}
