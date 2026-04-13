package com.asm.delivery.controller;

import com.asm.delivery.dto.response.RouteResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.route.RouteExecutionService;
import com.asm.delivery.service.route.RoutePlanningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/driver/routes")
@Tag(name = "Driver Routes", description = "Driver route execution endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DriverRouteController {

    private final RouteExecutionService routeExecutionService;
    private final RoutePlanningService routePlanningService;

    @GetMapping("/today")
    @Operation(summary = "Get today's route for authenticated driver")
    public ResponseEntity<RouteResponse> getToday(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(routeExecutionService.getTodayForDriver(UUID.fromString(principal.getUserId())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get route detail")
    public ResponseEntity<RouteResponse> get(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(routePlanningService.getForDriver(id, UUID.fromString(principal.getUserId())));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Start route (VALIDATED -> IN_PROGRESS)")
    public ResponseEntity<RouteResponse> start(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(routeExecutionService.start(id, UUID.fromString(principal.getUserId()), principal));
    }

    @PostMapping("/{id}/stops/{stopId}/arrive")
    @Operation(summary = "Mark stop as arrived")
    public ResponseEntity<RouteResponse> arrive(
            @PathVariable UUID id,
            @PathVariable UUID stopId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(routeExecutionService.arrive(id, stopId, UUID.fromString(principal.getUserId()), principal));
    }
}
