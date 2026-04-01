package com.asm.delivery.controller;

import com.asm.delivery.dto.response.DeliveryResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.dto.response.TrackingPointResponse;
import com.asm.delivery.dto.response.ProofOfDeliveryResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.DeliveryQueryService;
import com.asm.delivery.service.ProofOfDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/deliveries")
@Tag(name = "Deliveries", description = "Delivery status, tracking and history (client + driver)")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryQueryService queryService;
    private final ProofOfDeliveryService podService;

    @GetMapping("/{id}")
    @Operation(summary = "Get delivery details")
    public ResponseEntity<DeliveryResponse> getDelivery(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(queryService.getDelivery(id, principal.getUserId(), principal.getRole()));
    }

    @GetMapping("/{id}/tracking")
    @Operation(summary = "Get GPS tracking points for a delivery")
    public ResponseEntity<List<TrackingPointResponse>> getTracking(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(queryService.getTracking(id, principal.getUserId(), principal.getRole()));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Get status change history for a delivery")
    public ResponseEntity<List<StatusHistoryResponse>> getHistory(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(queryService.getHistory(id, principal.getUserId(), principal.getRole()));
    }

    @GetMapping("/{id}/pod")
    @Operation(summary = "Get proof of delivery (POD)", description = "CLIENT and DRIVER roles. Hides photo/signature for CLIENT.")
    public ResponseEntity<ProofOfDeliveryResponse> getPod(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(podService.getPod(id, principal.getRole()));
    }
}
