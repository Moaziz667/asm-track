package com.asm.delivery.controller;

import com.asm.delivery.dto.response.TrackingResponse;
import com.asm.delivery.service.PublicTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicTrackingController {

    private final PublicTrackingService service;

    @GetMapping("/track/{deliveryId}")
    public ResponseEntity<TrackingResponse> track(@PathVariable UUID deliveryId) {
        return ResponseEntity.ok(service.getTracking(deliveryId));
    }
}
