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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/ops")
@Tag(name = "Admin Ops", description = "Operations dashboard decision-ready APIs")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminOpsController {
    private final OpsAnalyticsService opsAnalyticsService;
    private final ExceptionResolutionService exceptionResolutionService;
    @GetMapping("/overview")
    @Operation(summary = "Ops overview with SLA, lanes and prioritized alerts")
    public ResponseEntity<AdminOpsOverviewResponse> overview(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer waitingSlaMinutes,
            @RequestParam(required = false) Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsOverview(period, from, to, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/lanes")
    @Operation(summary = "Dispatch lanes counters with top delivery cards")
    public ResponseEntity<AdminOpsLanesResponse> lanes(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "4") Integer topItems,
            @RequestParam(required = false) Integer waitingSlaMinutes,
            @RequestParam(required = false) Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsLanes(period, from, to, topItems, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/alerts")
    @Operation(summary = "Prioritized ops alerts with SLA context")
    public ResponseEntity<AdminOpsAlertsResponse> alerts(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "30") Integer limit,
            @RequestParam(required = false) Integer waitingSlaMinutes,
            @RequestParam(required = false) Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsAlerts(period, from, to, limit, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/audit")
    @Operation(summary = "Operational audit timeline with filtering")
    public ResponseEntity<AdminOpsAuditResponse> audit(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) DeliveryStatus status
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsAudit(period, from, to, limit, actor, role, status));
    }

    @GetMapping("/exceptions")
    @Operation(summary = "Operational exceptions feed with dedicated filters")
    public ResponseEntity<AdminOpsExceptionsResponse> exceptions(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestParam(required = false) String motif,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) String zone
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsExceptions(period, from, to, limit, motif, driverId, zone));
    }

    @PostMapping("/exceptions/{deliveryId}/reassign")
    @Operation(summary = "Quick action: reassign exception to another driver")
    public ResponseEntity<AdminOpsExceptionsResponse.ExceptionItem> reassignException(
            @PathVariable UUID deliveryId,
            @Valid @RequestBody AdminExceptionReassignRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(exceptionResolutionService.reassignException(deliveryId, request, principal));
    }

    @PostMapping("/exceptions/{deliveryId}/replan")
    @Operation(summary = "Quick action: move exception back to waiting lane")
    public ResponseEntity<AdminOpsExceptionsResponse.ExceptionItem> replanException(
            @PathVariable UUID deliveryId,
            @Valid @RequestBody AdminExceptionReplanRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(exceptionResolutionService.replanException(deliveryId, request, principal));
    }

}
