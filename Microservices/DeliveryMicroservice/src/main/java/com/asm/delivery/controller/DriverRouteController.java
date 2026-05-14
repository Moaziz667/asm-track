package com.asm.delivery.controller;

import com.asm.delivery.idempotency.IdempotentOperation;
import com.asm.delivery.dto.response.RouteResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.RoutePdfService;
import com.asm.delivery.service.route.RouteExecutionService;
import com.asm.delivery.service.route.RoutePlanningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/driver/routes")
@Tag(name = "Driver Routes", description = "Driver route execution endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DriverRouteController {

    private final RouteExecutionService routeExecutionService;
    private final RoutePlanningService routePlanningService;
    private final RoutePdfService routePdfService;

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
    @IdempotentOperation
    @Operation(summary = "Start route (VALIDATED -> IN_PROGRESS)")
    public ResponseEntity<RouteResponse> start(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(routeExecutionService.start(id, UUID.fromString(principal.getUserId()), principal));
    }

    @PostMapping("/{id}/stops/{stopId}/arrive")
    @IdempotentOperation
    @Operation(summary = "Mark stop as arrived")
    public ResponseEntity<RouteResponse> arrive(
            @PathVariable UUID id,
            @PathVariable UUID stopId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(routeExecutionService.arrive(id, stopId, UUID.fromString(principal.getUserId()), principal));
    }

    @GetMapping(value = "/{id}/pdf", produces = "application/pdf")
    @Operation(summary = "Download route manifest PDF")
    public ResponseEntity<byte[]> getPdf(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        byte[] pdf = routePdfService.generate(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"route-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping
    @Operation(summary = "List routes for authenticated driver within a date range")
    public ResponseEntity<List<RouteResponse>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID driverId = UUID.fromString(principal.getUserId());
        return ResponseEntity.ok(routePlanningService.list(null, driverId, null, from, to, null));
    }
}
