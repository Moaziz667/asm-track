package com.asm.delivery.controller;

import com.asm.delivery.dto.request.AdminExceptionReassignRequest;
import com.asm.delivery.dto.request.AdminExceptionReplanRequest;
import com.asm.delivery.dto.response.AdminOpsAuditResponse;
import com.asm.delivery.dto.response.AdminOpsExceptionsResponse;
import com.asm.delivery.dto.response.AdminOpsOverviewResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.analytics.OpsAnalyticsService;
import com.asm.delivery.service.dispatch.ExceptionResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ops")
@Tag(
    name = "Admin — Operations & Dispatch",
    description = """
        Real-time operations dashboard APIs used by the dispatch desk.
        All endpoints are scoped to the authenticated company and accept a `period`
        parameter (day / week / month / year) or explicit `from`/`to` date range.

        **SLA parameters:** `waitingSlaMinutes` and `transitSlaMinutes` override the
        company-level defaults configured in `/api/admin/reports/settings`.
        """
)
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminOpsController {

    private final OpsAnalyticsService opsAnalyticsService;
    private final ExceptionResolutionService exceptionResolutionService;
    private final com.asm.delivery.service.dispatch.NearestDriverService nearestDriverService;
    private final DeliveryRepository deliveryRepository;

    @GetMapping("/overview")
    @Operation(
        summary = "Full ops overview",
        description = "Returns a single consolidated payload used by the operations dashboard: SLA compliance rate, dispatch lane counters, top exceptions, and recent alerts. Designed to be polled every 30–60 seconds."
    )
    public ResponseEntity<AdminOpsOverviewResponse> overview(
            @org.springframework.web.bind.annotation.ModelAttribute com.asm.delivery.dto.analytics.AnalyticsQuery query
    ) {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(opsAnalyticsService.getOpsOverview(query));
    }

    @GetMapping("/audit")
    @Operation(
        summary = "Operational audit timeline",
        description = "Returns a chronological timeline of all status changes, assignments, and system actions. Filterable by actor, role, and delivery status. Used for compliance and post-incident review."
    )
    public ResponseEntity<AdminOpsAuditResponse> audit(
            @Parameter(description = "Time period", example = "day")
            @RequestParam(required = false, defaultValue = "today") String period,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Max events to return", example = "50")
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @Parameter(description = "Filter by actor name or email", example = "Aziz")
            String actor,
            @Parameter(description = "Filter by actor role", schema = @Schema(allowableValues = {"ADMIN","DRIVER","SYSTEM"}))
            String role,
            @Parameter(description = "Filter by delivery status at time of event")
            DeliveryStatus status
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsAudit(period, from, to, limit, actor, role, status));
    }

    @GetMapping("/exceptions")
    @Operation(
        summary = "Exceptions feed",
        description = "Returns failed, partial, and returned deliveries that require dispatcher action. Each exception includes the failure reason, driver info, and available quick actions (reassign, replan)."
    )
    public ResponseEntity<AdminOpsExceptionsResponse> exceptions(
            @Parameter(description = "Time period", example = "day")
            @RequestParam(required = false, defaultValue = "today") String period,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Max exceptions to return", example = "50")
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @Parameter(description = "Filter by failure reason/motif", example = "CLIENT_ABSENT")
            String motif,
            @Parameter(description = "Filter by driver ID")
            UUID driverId,
            @Parameter(description = "Filter by delivery zone name", example = "Tunis Nord")
            String zone
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsExceptions(period, from, to, limit, motif, driverId, zone));
    }

    @PostMapping("/exceptions/{deliveryId}/reassign")
    @Operation(
        summary = "Reassign exception to another driver",
        description = "Quick action from the exceptions feed: reassigns a failed delivery directly to a different driver. The delivery moves to SCHEDULED status. The new driver receives a push notification."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery reassigned"),
        @ApiResponse(responseCode = "404", description = "Delivery or driver not found", content = @Content)
    })
    @com.asm.delivery.idempotency.IdempotentOperation
    public ResponseEntity<AdminOpsExceptionsResponse.ExceptionItem> reassignException(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID deliveryId,
            @Valid @RequestBody AdminExceptionReassignRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(exceptionResolutionService.reassignException(deliveryId, request, principal));
    }

    @GetMapping("/exceptions/{deliveryId}/nearest-drivers")
    @Operation(
        summary = "Rank drivers by road proximity to a delivery's drop-off",
        description = "Powers the quick-reassign picker's recommended pick. Returns online drivers that have a "
            + "live GPS fix, ranked by road travel time (OSRM Table API) from their current position to the "
            + "delivery's drop-off. A straight-line k-NN pre-filter trims the fleet to a shortlist before the "
            + "matrix call. Falls back to straight-line ordering when OSRM is disabled or a leg is unroutable; "
            + "`etaSeconds`/`distanceMeters` are null when unavailable and `source` is \"osrm\" or \"haversine\". "
            + "The delivery's current driver and offline / GPS-less drivers are excluded."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ranked drivers (may be empty if the drop-off is ungeocoded or no driver is online with GPS)"),
        @ApiResponse(responseCode = "404", description = "Delivery not found", content = @Content)
    })
    public ResponseEntity<java.util.List<com.asm.delivery.dto.response.NearestDriverResponse>> nearestDrivers(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID deliveryId,
            @Parameter(description = "Max drivers to return", example = "5") @RequestParam(required = false, defaultValue = "5") int limit
    ) {
        return ResponseEntity.ok(nearestDriverService.nearest(deliveryId, limit));
    }

    @PostMapping("/exceptions/{deliveryId}/replan")
    @Operation(
        summary = "Move exception back to waiting queue",
        description = "Quick action: resets a failed delivery back to UNSCHEDULED so it can be reassigned later. Optionally reassigns to a specific zone."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery moved to waiting queue"),
        @ApiResponse(responseCode = "404", description = "Delivery not found", content = @Content)
    })
    @com.asm.delivery.idempotency.IdempotentOperation
    public ResponseEntity<AdminOpsExceptionsResponse.ExceptionItem> replanException(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID deliveryId,
            @Valid @RequestBody AdminExceptionReplanRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(exceptionResolutionService.replanException(deliveryId, request, principal));
    }

    @GetMapping("/live-stops")
    @Transactional(readOnly = true)
    @Operation(summary = "Active delivery stops for the live dispatch map")
    public ResponseEntity<List<LiveStopDTO>> liveStops() {
        List<Delivery> deliveries = deliveryRepository.findActiveDeliveriesWithOrder(
            List.of(DeliveryStatus.SCHEDULED, DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT)
        );
        List<LiveStopDTO> result = deliveries.stream()
            .filter(d -> d.getOrder() != null && d.getOrder().getDropoffLat() != null && d.getOrder().getDropoffLng() != null)
            .map(d -> new LiveStopDTO(
                d.getId().toString(),
                d.getStatus().name(),
                d.getOrder().getClientName(),
                d.getOrder().getDropoffCity(),
                d.getDriverId() != null ? d.getDriverId().toString() : null,
                d.getOrder().getDropoffLat().doubleValue(),
                d.getOrder().getDropoffLng().doubleValue(),
                d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null
            ))
            .toList();
        return ResponseEntity.ok(result);
    }

    public record LiveStopDTO(
        String deliveryId,
        String status,
        String clientName,
        String city,
        String driverId,
        Double dropoffLat,
        Double dropoffLng,
        String updatedAt
    ) {}
}
