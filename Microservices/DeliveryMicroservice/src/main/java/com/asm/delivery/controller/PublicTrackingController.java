package com.asm.delivery.controller;

import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.service.PublicTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Tag(name = "Public · Tracking", description = "Public delivery tracking — no authentication required. The "
        + "deliveryId acts as the unguessable access token, so recipients can follow a delivery by its link.")
public class PublicTrackingController {

    private final PublicTrackingService service;

    @GetMapping("/track/{deliveryId}")
    @Operation(summary = "Track a delivery",
            description = "Returns the public tracking view (status, ETA, driver position when live) for the "
                    + "delivery. No auth — the id is the shareable tracking token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tracking details"),
            @ApiResponse(responseCode = "404", description = "Unknown delivery id")
    })
    public ResponseEntity<TrackingResponse> track(@PathVariable UUID deliveryId) {
        return ResponseEntity.ok(service.getTracking(deliveryId));
    }
}
