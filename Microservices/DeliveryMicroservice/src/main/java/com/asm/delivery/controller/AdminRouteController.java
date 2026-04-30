package com.asm.delivery.controller;

import com.asm.delivery.dto.request.AddRouteStopRequest;
import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.dto.request.ReorderRouteStopsRequest;
import com.asm.delivery.dto.request.ReorderStopsRequest;
import com.asm.delivery.dto.request.SetRouteLockRequest;
import com.asm.delivery.dto.request.UpdateRouteRequest;
import com.asm.delivery.dto.response.OptimizeRouteResponse;
import com.asm.delivery.dto.response.RouteResponse;
import com.asm.delivery.dto.response.RouteFullResponse;
import com.asm.delivery.dto.response.RouteStopEtaResponse;
import com.asm.delivery.dto.response.SlaSummaryResponse;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.Tracking;
import com.asm.delivery.idempotency.IdempotentOperation;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.TrackingRepository;
import com.asm.delivery.service.RouteOptimizationService;
import com.asm.delivery.service.route.RoutePlanningService;
import com.asm.delivery.service.route.RouteExecutionService;
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

    private final RoutePlanningService routePlanningService;
    private final RouteExecutionService routeExecutionService;
    private final RoutePdfService routePdfService;
    private final RouteOptimizationService routeOptimizationService;
    private final RouteStopRepository routeStopRepository;
    private final TrackingRepository trackingRepository;
    private final com.asm.delivery.service.dispatch.DispatchService dispatchService;

    // ─── Existing CRUD ────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List routes")
    public ResponseEntity<List<RouteResponse>> list(
            @RequestParam(required = false) RouteStatus status,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String city
    ) {
        return ResponseEntity.ok(routePlanningService.list(status, driverId, date, from, to, city));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get route")
    public ResponseEntity<RouteResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(routePlanningService.get(id));
    }

    @GetMapping("/{id}/full")
    @Operation(summary = "Get full aggregated route")
    public ResponseEntity<RouteFullResponse> getRouteFull(@PathVariable UUID id) {
        return ResponseEntity.ok(routePlanningService.getRouteFull(id));
    }

    @PostMapping
    @Operation(summary = "Create route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> create(
            @Valid @RequestBody CreateRouteRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String createdBy) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routePlanningService.create(request, createdBy));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update route")
    public ResponseEntity<RouteResponse> update(@PathVariable UUID id, @RequestBody UpdateRouteRequest request) {
        return ResponseEntity.ok(routePlanningService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete route (draft only)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        routePlanningService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stops")
    @Operation(summary = "Add stop to route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> addStop(@PathVariable UUID id, @Valid @RequestBody AddRouteStopRequest request) {
        return ResponseEntity.ok(routePlanningService.addStop(id, request));
    }

    @DeleteMapping("/{id}/stops/{stopId}")
    @Operation(summary = "Remove stop from route")
    public ResponseEntity<RouteResponse> removeStop(@PathVariable UUID id, @PathVariable UUID stopId) {
        return ResponseEntity.ok(routePlanningService.removeStop(id, stopId));
    }

    @PatchMapping("/{id}/stops/{stopId}")
    @Operation(summary = "Patch stop time windows / buffer")
    public ResponseEntity<RouteResponse> patchStop(@PathVariable UUID id,
                                                    @PathVariable UUID stopId,
                                                    @RequestBody com.asm.delivery.dto.request.PatchRouteStopRequest request) {
        return ResponseEntity.ok(routePlanningService.patchStop(id, stopId, request));
    }

    @PutMapping("/{id}/stops/reorder")
    @Operation(summary = "Reorder route stops (draft only)")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> reorderDraft(@PathVariable UUID id,
                                                      @Valid @RequestBody ReorderRouteStopsRequest request) {
        return ResponseEntity.ok(routePlanningService.reorderStops(id, request.getStopIds()));
    }

    @PutMapping("/{id}/validate")
    @Operation(summary = "Validate route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> validate(@PathVariable UUID id) {
        return ResponseEntity.ok(routePlanningService.validate(id));
    }

    @PatchMapping("/{id}/lock")
    @Operation(summary = "Lock or unlock a route (excluded from batch optimization when locked)")
    public ResponseEntity<RouteResponse> setLocked(@PathVariable UUID id,
                                                   @Valid @RequestBody SetRouteLockRequest request) {
        return ResponseEntity.ok(routePlanningService.setLocked(id, Boolean.TRUE.equals(request.getLocked())));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close route")
    @IdempotentOperation
    public ResponseEntity<RouteResponse> close(@PathVariable UUID id) {
        return ResponseEntity.ok(routeExecutionService.close(id));
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
        return ResponseEntity.ok(routePlanningService.get(id));
    }

    @PutMapping("/{id}/reorder")
    @Operation(summary = "Manually reorder stops and recalculate ETAs/SLAs")
    public ResponseEntity<RouteResponse> reorder(@PathVariable UUID id,
                                                 @Valid @RequestBody ReorderStopsRequest request) {
        routeOptimizationService.applyManualReorder(id, request.getStopIds());
        return ResponseEntity.ok(routePlanningService.get(id));
    }

    @GetMapping("/{id}/eta-details")
    @Operation(summary = "Get ETA and SLA details for all stops")
    public ResponseEntity<List<RouteStopEtaResponse>> etaDetails(@PathVariable UUID id) {
        RouteResponse route = routePlanningService.get(id);
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
        return ResponseEntity.ok(routePlanningService.get(id));
    }

    @GetMapping("/{id}/driver-location")
    @Operation(summary = "Latest driver GPS location for an in-progress route (from tracking table)")
    public ResponseEntity<java.util.Map<String, Object>> driverLocation(@PathVariable UUID id) {
        RouteResponse route = routePlanningService.get(id);
        // Find the most recent non-terminal stop's delivery, then its latest tracking point
        java.util.Optional<UUID> activeDeliveryId = routeStopRepository
                .findByRouteIdOrderByStopOrderAsc(id)
                .stream()
                .filter(s -> s.getStatus() != RouteStopStatus.COMPLETED
                          && s.getStatus() != RouteStopStatus.FAILED
                          && s.getStatus() != RouteStopStatus.PARTIAL)
                .map(s -> s.getDeliveryId())
                .findFirst();

        if (activeDeliveryId.isEmpty()) {
            return ResponseEntity.ok(java.util.Map.of("found", false));
        }

        java.util.Optional<Tracking> latest = trackingRepository
                .findFirstByDeliveryIdOrderByTimestampDesc(activeDeliveryId.get());

        if (latest.isEmpty()) {
            return ResponseEntity.ok(java.util.Map.of("found", false));
        }

        Tracking t = latest.get();
        return ResponseEntity.ok(java.util.Map.of(
                "found", true,
                "lat", t.getLat(),
                "lng", t.getLng(),
                "updatedAt", t.getTimestamp().toString()
        ));
    }

    @GetMapping("/sla-summary")
    @Operation(summary = "Aggregated SLA status (ON_TIME / LATE) across all active route stops")
    public ResponseEntity<SlaSummaryResponse> slaSummary() {
        return ResponseEntity.ok(routePlanningService.getSlaSummary());
    }

    // ─── Stop cancellation ────────────────────────────────────────────────────────

    @PostMapping("/{id}/stops/{stopId}/cancel")
    @Operation(summary = "Cancel a single stop — SCHEDULED/PENDING/ARRIVED → UNSCHEDULED, PICKED_UP → returnToOrigin. IN_TRANSIT is rejected.")
    public ResponseEntity<RouteResponse> cancelStop(
            @PathVariable UUID id,
            @PathVariable UUID stopId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(routePlanningService.cancelStop(id, stopId, reason));
    }

    /** @deprecated Use /transfer-stops instead */
    @Deprecated
    @PostMapping("/{id}/reassign")
    @Operation(summary = "Reassign route to a different driver (Deprecated — use /transfer-stops)")
    public ResponseEntity<RouteResponse> reassign(@PathVariable UUID id,
            @RequestParam UUID newDriverId) {
        return ResponseEntity.ok(routePlanningService.reassign(id, newDriverId));
    }

    @PostMapping("/transfer-stops")
    @Operation(summary = "Unified endpoint to transfer specific stops from one route/driver to another")
    public ResponseEntity<com.asm.delivery.dto.response.TransferStopsResponse> transferStops(
            @Valid @RequestBody com.asm.delivery.dto.request.TransferStopsRequest request) {
        return ResponseEntity.ok(dispatchService.transferStops(request));
    }

    @PostMapping("/{id}/stops/active")
    @Operation(summary = "Add a stop to a VALIDATED or IN_PROGRESS route — notifies driver via WebSocket")
    public ResponseEntity<RouteResponse> addStopToActive(@PathVariable UUID id,
            @Valid @RequestBody AddRouteStopRequest request) {
        return ResponseEntity.ok(routePlanningService.addStopToValidated(id, request));
    }

    // ─── Driver route list (date range) ──────────────────────────────────────────

    @GetMapping("/driver/{driverId}")
    @Operation(summary = "List routes for a specific driver within a date range")
    public ResponseEntity<List<RouteResponse>> listForDriver(
            @PathVariable UUID driverId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ResponseEntity.ok(routePlanningService.list(null, driverId, null, from, to, null));
    }
}
