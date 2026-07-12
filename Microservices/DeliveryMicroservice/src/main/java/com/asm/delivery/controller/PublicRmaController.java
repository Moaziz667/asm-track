package com.asm.delivery.controller;

import com.asm.delivery.dto.request.PublicReturnRequest;
import com.asm.delivery.dto.response.PublicReturnableItemsResponse;
import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.service.PublicReturnRateLimiter;
import com.asm.delivery.service.PublicRmaService;
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
@RequestMapping("/api/public/track/{deliveryId}/return")
@RequiredArgsConstructor
public class PublicRmaController {

    private final PublicRmaService service;
    private final PublicReturnRateLimiter rateLimiter;

    @GetMapping
    public ResponseEntity<PublicReturnableItemsResponse> getReturnableItems(@PathVariable UUID deliveryId) {
        return ResponseEntity.ok(service.getReturnableItems(deliveryId));
    }

    @PostMapping
    public ResponseEntity<TrackingResponse> createReturn(
            @PathVariable UUID deliveryId,
            @RequestBody @Valid PublicReturnRequest request,
            HttpServletRequest httpRequest) {
        enforceRateLimit(deliveryId, httpRequest);
        return ResponseEntity.ok(service.createReturn(deliveryId, request));
    }

    @PostMapping("/cancel")
    public ResponseEntity<TrackingResponse> cancelReturn(
            @PathVariable UUID deliveryId,
            HttpServletRequest httpRequest) {
        enforceRateLimit(deliveryId, httpRequest);
        return ResponseEntity.ok(service.cancelReturn(deliveryId));
    }

    @PostMapping("/photos")
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
