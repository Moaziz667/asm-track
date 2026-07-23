package com.asm.delivery.controller;

import com.asm.delivery.dto.request.PublicReturnRequest;
import com.asm.delivery.dto.response.PublicReturnableItemsResponse;
import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.service.PublicReturnRateLimiter;
import com.asm.delivery.service.PublicRmaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Public (unauthenticated) return endpoints for the client tracking page. Mounted under
 * {@code /api/public/**}, which is whitelisted in SecurityConfig. Write actions are IP-rate-limited.
 */
@RestController
@RequestMapping("/api/v1/public/track/{deliveryId}/return")
@RequiredArgsConstructor
@Tag(name = "Public · Returns", description = "Public (unauthenticated) self-service returns from the recipient's "
        + "tracking page. The deliveryId is the access token. Write actions are IP rate-limited to prevent abuse.")
public class PublicRmaController {

    private final PublicRmaService service;
    private final PublicReturnRateLimiter rateLimiter;

    @GetMapping
    @Operation(summary = "List returnable items",
            description = "Returns the items of the delivery that are eligible for return.")
    @ApiResponse(responseCode = "200", description = "Returnable items")
    public ResponseEntity<PublicReturnableItemsResponse> getReturnableItems(@PathVariable UUID deliveryId) {
        return ResponseEntity.ok(service.getReturnableItems(deliveryId));
    }

    @PostMapping
    @Operation(summary = "Request a return",
            description = "Opens a return request for the selected items. Rate-limited per IP.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Return created"),
            @ApiResponse(responseCode = "429", description = "Too many requests from this IP")
    })
    public ResponseEntity<TrackingResponse> createReturn(
            @PathVariable UUID deliveryId,
            @RequestBody @Valid PublicReturnRequest request,
            HttpServletRequest httpRequest) {
        enforceRateLimit(deliveryId, httpRequest);
        return ResponseEntity.ok(service.createReturn(deliveryId, request));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel my return request",
            description = "Cancels a pending return for this delivery. Rate-limited per IP.")
    @ApiResponse(responseCode = "200", description = "Return cancelled")
    public ResponseEntity<TrackingResponse> cancelReturn(
            @PathVariable UUID deliveryId,
            HttpServletRequest httpRequest) {
        enforceRateLimit(deliveryId, httpRequest);
        return ResponseEntity.ok(service.cancelReturn(deliveryId));
    }

    @PostMapping("/photos")
    @Operation(summary = "Upload return photos",
            description = "Attaches photos (multipart 'files') to the return, e.g. proof of item condition. Rate-limited per IP.")
    @ApiResponse(responseCode = "200", description = "Stored photo URLs")
    public ResponseEntity<List<String>> uploadPhotos(
            @PathVariable UUID deliveryId,
            @RequestParam("files") List<MultipartFile> files,
            HttpServletRequest httpRequest) {
        enforceRateLimit(deliveryId, httpRequest);
        return ResponseEntity.ok(service.uploadPhotos(deliveryId, files));
    }

    private void enforceRateLimit(UUID deliveryId, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire(clientIp(httpRequest), deliveryId)) {
            throw AppException.tooManyRequests("RATE_LIMITED", "Trop de demandes. Réessayez demain.");
        }
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}
