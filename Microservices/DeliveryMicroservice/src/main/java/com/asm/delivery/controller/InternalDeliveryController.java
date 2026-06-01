package com.asm.delivery.controller;

import com.asm.delivery.dto.response.ActiveMissionsDTO;
import com.asm.delivery.service.dispatch.DispatchService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint for other microservices to query active driver missions.
 * Protected by OAuth2 service token (role=SERVICE) validated by JwtAuthFilter + SecurityConfig.
 */
@RestController
@RequestMapping("/internal/deliveries")
@RequiredArgsConstructor
@Hidden
public class InternalDeliveryController {

    private final DispatchService dispatchService;

    @GetMapping("/active-missions")
    public ResponseEntity<Map<UUID, ActiveMissionsDTO>> getActiveMissions() {
        return ResponseEntity.ok(dispatchService.getActiveMissions());
    }
}
