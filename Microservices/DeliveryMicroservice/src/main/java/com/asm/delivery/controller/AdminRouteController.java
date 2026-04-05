package com.asm.delivery.controller;

import com.asm.delivery.dto.request.AddRouteStopRequest;
import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.dto.request.ReorderRouteStopsRequest;
import com.asm.delivery.dto.request.ReorderStopsRequest;
import com.asm.delivery.dto.request.UpdateRouteRequest;
import com.asm.delivery.dto.response.OptimizeRouteResponse;
import com.asm.delivery.dto.response.RouteResponse;
import com.asm.delivery.dto.response.RouteStopEtaResponse;
import com.asm.delivery.dto.response.SlaSummaryResponse;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.idempotency.IdempotentOperation;
import com.asm.delivery.service.RouteOptimizationService;
import com.asm.delivery.service.RouteService;
import com.asm.delivery.service.RoutePdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/routes")
@Tag(name = "Admin Routes", description = "Route/tournee management")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminRouteController {

    private final RouteService routeService;
    private final RoutePdfService routePdfService;
    private final RouteOptimizationService routeOptimizationService;

    // ─── Existing CRUD ────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List routes")
    public ResponseEntity<List<RouteResponse>> list(
            @RequestParam(required = false) RouteStatus status,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String city
    ) {
        return ResponseEntity.ok(routeService.list(status, driverId, date, city));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get route")
    public ResponseEntity<RouteResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(routeService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> create(
            @Valid @RequestBody CreateRouteRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String createdBy) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routeService.create(request, createdBy));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update route")
    public ResponseEntity<RouteResponse> update(@PathVariable UUID id, @RequestBody UpdateRouteRequest request) {
        return ResponseEntity.ok(routeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete route (draft only)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        routeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stops")
    @Operation(summary = "Add stop to route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> addStop(@PathVariable UUID id, @Valid @RequestBody AddRouteStopRequest request) {
        return ResponseEntity.ok(routeService.addStop(id, request.getDeliveryId()));
    }

    @DeleteMapping("/{id}/stops/{stopId}")
    @Operation(summary = "Remove stop from route")
    public ResponseEntity<RouteResponse> removeStop(@PathVariable UUID id, @PathVariable UUID stopId) {
        return ResponseEntity.ok(routeService.removeStop(id, stopId));
    }

    @PutMapping("/{id}/stops/reorder")
    @Operation(summary = "Reorder route stops (draft only)")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> reorderDraft(@PathVariable UUID id,
                                                      @Valid @RequestBody ReorderRouteStopsRequest request) {
        return ResponseEntity.ok(routeService.reorderStops(id, request.getStopIds()));
    }

    @PutMapping("/{id}/validate")
    @Operation(summary = "Validate route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> validate(@PathVariable UUID id) {
        return ResponseEntity.ok(routeService.validate(id));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> close(@PathVariable UUID id) {
        return ResponseEntity.ok(routeService.close(id));
    }

    @GetMapping("/{id}/pdf")
    @Operation(summary = "Feuille de route PDF")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        byte[] pdf = routePdfService.generate(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=route-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ─── Optimization endpoints ───────────────────────────────────────────────────

    @PostMapping("/{id}/optimize")
    @Operation(summary = "Suggest optimized stop order (does not apply)")
    public ResponseEntity<OptimizeRouteResponse> optimize(@PathVariable UUID id) {
        return ResponseEntity.ok(routeOptimizationService.suggestOptimization(id));
    }

    @PutMapping("/{id}/apply-optimization")
    @Operation(summary = "Apply optimized stop order and recalculate ETAs/SLAs")
    public ResponseEntity<RouteResponse> applyOptimization(@PathVariable UUID id) {
        routeOptimizationService.applyOptimization(id);
        return ResponseEntity.ok(routeService.get(id));
    }

    @PutMapping("/{id}/reorder")
    @Operation(summary = "Manually reorder stops and recalculate ETAs/SLAs")
    public ResponseEntity<RouteResponse> reorder(@PathVariable UUID id,
                                                 @Valid @RequestBody ReorderStopsRequest request) {
        routeOptimizationService.applyManualReorder(id, request.getStopIds());
        return ResponseEntity.ok(routeService.get(id));
    }

    @GetMapping("/{id}/eta-details")
    @Operation(summary = "Get ETA and SLA details for all stops")
    public ResponseEntity<List<RouteStopEtaResponse>> etaDetails(@PathVariable UUID id) {
        RouteResponse route = routeService.get(id);
        List<RouteStopEtaResponse> details = route.getStops().stream()
                .map(s -> RouteStopEtaResponse.builder()
                        .stopId(s.getId())
                        .sequenceOrder(s.getStopOrder())
                        .deliveryAddress(s.getDeliveryAddress())
                        .etaAt(s.getEtaAt())
                        .slaDeadline(s.getSlaDeadline())
                        .slaStatus(s.getSlaStatus())
                        .driveDurationSeconds(s.getDriveDurationSeconds())
                        .driveDistanceMeters(s.getDriveDistanceMeters())
                        .actualArrivalAt(s.getActualArrivalAt())
                        .status(s.getStatus())
                        .dwellMinutes(s.getDwellMinutes())
                        .build())
                .toList();
        return ResponseEntity.ok(details);
    }

    @PostMapping("/{id}/recalculate")
    @Operation(summary = "Recalculate ETAs and SLAs from current departure time")
    public ResponseEntity<RouteResponse> recalculate(@PathVariable UUID id) {
        routeOptimizationService.recalculate(id);
        return ResponseEntity.ok(routeService.get(id));
    }

    @GetMapping("/sla-summary")
    @Operation(summary = "Aggregated SLA status (ON_TIME / AT_RISK / BREACHED) across all active route stops")
    public ResponseEntity<SlaSummaryResponse> slaSummary() {
        return ResponseEntity.ok(routeService.getSlaSummary());
    }
}
