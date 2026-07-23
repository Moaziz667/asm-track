package com.asm.delivery.controller;

import com.asm.delivery.dto.request.HandoffConfirmRequest;
import com.asm.delivery.dto.response.HandoffResponse;
import com.asm.delivery.dto.response.HandoffTokenResponse;
import com.asm.delivery.entity.Handoff;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.HandoffService;
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

/**
 * Handoff endpoints for the driver app. Live updates are pushed over the
 * driver's STOMP topic; the GET here is for initial load only (no polling).
 */
@RestController
@RequestMapping("/api/v1/driver/handoffs")
@Tag(name = "Driver Handoffs", description = "Custody transfer between drivers")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class HandoffController {

    private final HandoffService handoffService;

    @GetMapping
    @Operation(summary = "List my open handoffs (incoming to receive + outgoing to give)")
    public ResponseEntity<List<HandoffResponse>> myHandoffs(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(handoffService.listForDriver(UUID.fromString(principal.getUserId())));
    }

    @PostMapping("/{id}/token")
    @Operation(summary = "Sender generates the one-time handoff code")
    public ResponseEntity<HandoffTokenResponse> generateToken(
            @PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        Handoff h = handoffService.generateToken(id, UUID.fromString(principal.getUserId()));
        return ResponseEntity.ok(HandoffTokenResponse.builder()
                .token(h.getToken())
                .deliveryId(h.getDeliveryId() != null ? h.getDeliveryId().toString() : null)
                .expiresAt(h.getTokenExpiresAt())
                .build());
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Receiver confirms physical receipt with the sender's code")
    public ResponseEntity<Void> confirm(
            @PathVariable UUID id,
            @Valid @RequestBody HandoffConfirmRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        handoffService.confirm(id, UUID.fromString(principal.getUserId()),
                req.getToken(), req.getLat(), req.getLng(), null, req.getNotes());
        return ResponseEntity.ok().build();
    }
}
