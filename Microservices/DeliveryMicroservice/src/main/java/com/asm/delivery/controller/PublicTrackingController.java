package com.asm.delivery.controller;

import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.service.PublicTrackingRateLimiter;
import com.asm.delivery.service.PublicTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final PublicTrackingRateLimiter rateLimiter;

    @GetMapping("/track/{deliveryId}")
    @Operation(summary = "Track a delivery",
            description = "Returns the public tracking view (status, ETA, driver position when live) for the "
                    + "delivery. No auth — the id is the shareable tracking token. Rate limited per "
                    + "caller IP and delivery.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tracking details"),
            @ApiResponse(responseCode = "404", description = "Unknown delivery id"),
            @ApiResponse(responseCode = "429", description = "Too many requests for this delivery")
    })
    public ResponseEntity<TrackingResponse> track(@PathVariable UUID deliveryId,
                                                  HttpServletRequest httpRequest) {
        // The sibling public endpoints (returns) have been rate limited from the start; this one had
        // not been, though it discloses more: the driver's name, phone and live position. A leaked
        // link could therefore be polled indefinitely to follow a person. See
        // PublicTrackingRateLimiter for why the budget is deliberately generous.
        if (!rateLimiter.tryAcquire(clientIp(httpRequest), deliveryId)) {
            throw AppException.tooManyRequests("RATE_LIMITED", "Trop de demandes. Réessayez dans quelques minutes.");
        }
        return ResponseEntity.ok(service.getTracking(deliveryId));
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}
