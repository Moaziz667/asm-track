package com.asm.delivery.controller;

import com.asm.delivery.dto.request.AdminExceptionReassignRequest;
import com.asm.delivery.dto.request.AdminExceptionReplanRequest;
import com.asm.delivery.dto.response.AdminOpsAlertsResponse;
import com.asm.delivery.dto.response.AdminOpsAuditResponse;
import com.asm.delivery.dto.response.AdminOpsExceptionsResponse;
import com.asm.delivery.dto.response.AdminOpsLanesResponse;
import com.asm.delivery.dto.response.AdminOpsOverviewResponse;
import com.asm.delivery.entity.DeliveryStatus;
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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/ops")
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

    @GetMapping("/overview")
    @Operation(
        summary = "Full ops overview",
        description = "Returns a single consolidated payload used by the operations dashboard: SLA compliance rate, dispatch lane counters, top exceptions, and recent alerts. Designed to be polled every 30–60 seconds."
    )
    public ResponseEntity<AdminOpsOverviewResponse> overview(
            @Parameter(description = "Time period", schema = @Schema(allowableValues = {"day","week","month","year"}), example = "day")
            @RequestParam(required = false, defaultValue = "day") String period,
            @Parameter(description = "Start date (overrides period)", example = "2026-05-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (overrides period)", example = "2026-05-13")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Waiting SLA threshold in minutes (overrides company default)", example = "30")
            @RequestParam(required = false) Integer waitingSlaMinutes,
            @Parameter(description = "Transit SLA threshold in minutes (overrides company default)", example = "60")
            @RequestParam(required = false) Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsOverview(period, from, to, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/lanes")
    @Operation(
        summary = "Dispatch lanes with counters",
        description = "Returns the Kanban-style dispatch lanes: WAITING, IN_TRANSIT, DELIVERED, FAILED — each with a count and top delivery cards. Used by the dispatch desk board."
    )
    public ResponseEntity<AdminOpsLanesResponse> lanes(
            @Parameter(description = "Time period", example = "day")
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Number of top deliveries to return per lane", example = "4")
            @RequestParam(required = false, defaultValue = "4") Integer topItems,
            @RequestParam(required = false) Integer waitingSlaMinutes,
            @RequestParam(required = false) Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsLanes(period, from, to, topItems, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/alerts")
    @Operation(
        summary = "Prioritized ops alerts",
        description = "Returns a prioritized alert feed: SLA breaches (CRITICAL), late deliveries (WARNING), and informational events (INFO). Used by the AlertBell and the dispatch desk sidebar."
    )
    public ResponseEntity<AdminOpsAlertsResponse> alerts(
            @Parameter(description = "Time period", example = "day")
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Max alerts to return", example = "30")
            @RequestParam(required = false, defaultValue = "30") Integer limit,
            @RequestParam(required = false) Integer waitingSlaMinutes,
            @RequestParam(required = false) Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsAlerts(period, from, to, limit, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/audit")
    @Operation(
        summary = "Operational audit timeline",
        description = "Returns a chronological timeline of all status changes, assignments, and system actions. Filterable by actor, role, and delivery status. Used for compliance and post-incident review."
    )
    public ResponseEntity<AdminOpsAuditResponse> audit(
            @Parameter(description = "Time period", example = "day")
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Max events to return", example = "50")
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @Parameter(description = "Filter by actor name or email", example = "Aziz")
            @RequestParam(required = false) String actor,
            @Parameter(description = "Filter by actor role", schema = @Schema(allowableValues = {"ADMIN","DRIVER","SYSTEM"}))
            @RequestParam(required = false) String role,
            @Parameter(description = "Filter by delivery status at time of event")
            @RequestParam(required = false) DeliveryStatus status
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
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Max exceptions to return", example = "50")
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @Parameter(description = "Filter by failure reason/motif", example = "CLIENT_ABSENT")
            @RequestParam(required = false) String motif,
            @Parameter(description = "Filter by driver ID")
            @RequestParam(required = false) UUID driverId,
            @Parameter(description = "Filter by delivery zone name", example = "Tunis Nord")
            @RequestParam(required = false) String zone
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
}
