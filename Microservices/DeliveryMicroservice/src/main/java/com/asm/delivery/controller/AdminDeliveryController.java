package com.asm.delivery.controller;

import com.asm.delivery.dto.request.AssignDeliveryRequest;
import com.asm.delivery.dto.request.PinDropoffRequest;
import com.asm.delivery.dto.response.AdminDeliveryDetailResponse;
import com.asm.delivery.dto.response.AdminDeliverySummaryResponse;
import com.asm.delivery.dto.response.AdminDriverResponse;
import com.asm.delivery.dto.response.AdminOpsOverviewResponse;
import com.asm.delivery.dto.response.AdminStatsResponse;
import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.dto.response.ProofOfDeliveryResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.OrderSource;
import com.asm.delivery.idempotency.IdempotentOperation;
import com.asm.delivery.service.analytics.OpsAnalyticsService;
import com.asm.delivery.service.dispatch.DispatchService;
import com.asm.delivery.service.dispatch.ExceptionResolutionService;
import com.asm.delivery.service.BonLivraisonPdfService;
import com.asm.delivery.service.GeocodingService;
import com.asm.delivery.service.ProofOfDeliveryService;
import com.asm.delivery.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/deliveries")
@Tag(name = "Admin Deliveries", description = "Monitor, dispatch, and manage deliveries. All data is scoped to the authenticated admin's company.")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminDeliveryController {
    private final DispatchService dispatchService;
    private final OpsAnalyticsService opsAnalyticsService;
    private final ExceptionResolutionService exceptionResolutionService;
    private final ProofOfDeliveryService podService;
    private final GeocodingService geocodingService;
    private final BonLivraisonPdfService bonLivraisonPdfService;
    private final com.asm.delivery.service.OrderService orderService;
    private final com.asm.delivery.service.OrderGeocodingService orderGeocodingService;

    @PostMapping("/re-geocode-missing")
    @Operation(summary = "Re-run auto-geocoding for all unlocated orders",
            description = "Queues every order with no coordinates for geocoding (full address, then city fallback). Returns how many were queued.")
    public ResponseEntity<java.util.Map<String, Integer>> reGeocodeMissing() {
        int queued = orderGeocodingService.reEnrichMissing();
        return ResponseEntity.ok(java.util.Map.of("queued", queued));
    }

    @GetMapping
    @Operation(
        summary = "Search and monitor deliveries",
        description = """
            Paginated search across all deliveries for the company. Supports combining multiple filters.
            Used by the dispatch desk, deliveries table, and reporting screens.
            Results are sorted by creation date descending by default.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of deliveries matching the filters")
    })
    public ResponseEntity<Page<AdminDeliverySummaryResponse>> list(
            @Parameter(description = "Filter by delivery status", schema = @Schema(implementation = DeliveryStatus.class))
            DeliveryStatus status,

            @Parameter(description = "Filter by assigned driver ID")
            UUID driverId,

            @Parameter(description = "Filter by creation date (ISO format: yyyy-MM-dd)", example = "2026-05-13")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,

            @Parameter(description = "Filter by order source", schema = @Schema(allowableValues = {"ODOO", "APP"}))
            OrderSource source,

            @Parameter(description = "Filter by delivery zone ID")
            UUID zoneId,

            @Parameter(description = "If true, only return deliveries with no GPS coordinates pinned yet")
            Boolean unpinned,

            @Parameter(description = "Free-text search across client name, city, ERP order id and BL number")
            @RequestParam(required = false) String q,

            @Parameter(description = "Filter by driver assignment: true = assigned, false = unassigned")
            @RequestParam(required = false) Boolean assigned,

            @Parameter(description = "Scheduled-date quick view: OVERDUE | TODAY | FUTURE (pending deliveries only)")
            @RequestParam(required = false) String bucket,

            @ParameterObject Pageable pageable
    ) {
        return ResponseEntity.ok(dispatchService.searchDeliveries(status, driverId, date, source, zoneId, unpinned, q, assigned, bucket, pageable));
    }

    @GetMapping("/calendar")
    @Operation(
        summary = "Deliveries scheduled within a date range (calendar/overview month view)",
        description = "Returns deliveries whose effective scheduled date (rescheduled ∨ scheduled ∨ created) falls within [from, to]."
    )
    public ResponseEntity<java.util.List<AdminDeliverySummaryResponse>> calendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ResponseEntity.ok(dispatchService.calendar(from, to));
    }

    @GetMapping("/counts")
    @Operation(
        summary = "Quick-view counts for the deliveries table",
        description = """
            Returns tallies (all, needsPinning, unassigned, inTransit, completed, failed, overdue, today, future)
            under the current base filters (driver / date / source / zone / search). Computed across the whole
            dataset so the sidebar reflects every matching delivery, not just the loaded page.
            """
    )
    public ResponseEntity<java.util.Map<String, Long>> counts(
            UUID driverId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            OrderSource source,
            UUID zoneId,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(dispatchService.deliveryCounts(driverId, date, source, zoneId, q));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get delivery full detail", description = "Returns the delivery with its full order data, assigned driver, route info, status history, and POD summary.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery detail"),
        @ApiResponse(responseCode = "404", description = "Delivery not found", content = @Content)
    })
    public ResponseEntity<AdminDeliveryDetailResponse> detail(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID id) {
        return ResponseEntity.ok(dispatchService.getDeliveryDetail(id));
    }

    @GetMapping("/{id}/geocode")
    @Operation(summary = "Geocode delivery address", description = "Uses Nominatim (OpenStreetMap) to find GPS coordinates for this delivery's dropoff address. Always show results on a map for admin confirmation before pinning.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Geocoding suggestion with lat/lng and formatted address"),
        @ApiResponse(responseCode = "404", description = "Delivery not found", content = @Content)
    })
    public ResponseEntity<GeocodeSuggestionResponse> geocode(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID id) {
        AdminDeliveryDetailResponse delivery = dispatchService.getDeliveryDetail(id);
        String query = buildGeocodeQuery(delivery.getDropoffAddress(), delivery.getDropoffCity());
        return ResponseEntity.ok(geocodingService.geocode(query));
    }

    @GetMapping("/geocode-search")
    @Operation(summary = "Free-text address autocomplete",
            description = "Server-side proxy to Nominatim (Tunisia) so the browser never calls the public geocoder directly. Returns up to {limit} suggestions for an address query.")
    public ResponseEntity<List<GeocodeSuggestionResponse>> geocodeSearch(
            @RequestParam String q,
            @RequestParam(required = false, defaultValue = "5") int limit) {
        return ResponseEntity.ok(geocodingService.searchAddresses(q, limit));
    }

    @GetMapping("/reverse-geocode")
    @Operation(summary = "Reverse geocode coordinates to address", description = "Converts GPS coordinates to a human-readable address, city, and postal code using Nominatim.")
    public ResponseEntity<GeocodeSuggestionResponse> reverseGeocode(
            @Parameter(description = "Latitude", example = "36.8065") @RequestParam double lat,
            @Parameter(description = "Longitude", example = "10.1815") @RequestParam double lng) {
        return ResponseEntity.ok(geocodingService.reverseGeocode(lat, lng));
    }

    private static String buildGeocodeQuery(String address, String city) {
        StringBuilder q = new StringBuilder();
        if (address != null && !address.isBlank()) q.append(address.trim());
        if (city != null && !city.isBlank()) {
            if (!q.isEmpty()) q.append(", ");
            q.append(city.trim());
        }
        if (!q.isEmpty()) q.append(", Tunisia");
        return q.toString();
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "Assign delivery to a driver", description = "Assigns a delivery in UNSCHEDULED or WAITING_DRIVER status to a driver. The driver receives a push notification. Idempotent — safe to retry.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery assigned — status changes to SCHEDULED"),
        @ApiResponse(responseCode = "400", description = "Delivery is not in an assignable status", content = @Content),
        @ApiResponse(responseCode = "404", description = "Delivery or driver not found", content = @Content)
    })
    @IdempotentOperation
    public ResponseEntity<AdminDeliveryDetailResponse> assign(
            @PathVariable UUID id,
            @Valid @RequestBody AssignDeliveryRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(dispatchService.assignDelivery(id, request, principal));
    }

    @PostMapping("/{id}/pin-dropoff")
    @Operation(summary = "Pin delivery GPS coordinates", description = "Manually set the dropoff GPS coordinates and normalized address. Use after confirming the geocoding result on the map. Required before the driver can navigate to the client.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Coordinates saved — dropoffPinned becomes true"),
        @ApiResponse(responseCode = "404", description = "Delivery not found", content = @Content)
    })
    @IdempotentOperation
    public ResponseEntity<AdminDeliveryDetailResponse> pinDropoff(
            @PathVariable UUID id,
            @Valid @RequestBody PinDropoffRequest request) {
        dispatchService.pinDropoff(id, request);
        return ResponseEntity.ok(dispatchService.getDeliveryDetail(id));
    }

    @PostMapping("/{id}/confirm-return")
    @Operation(summary = "Confirm returned parcel received", description = "Marks a returned parcel as physically received at the depot. Clears the return flag and allows the delivery to be re-dispatched to another driver.")
    @IdempotentOperation
    public ResponseEntity<Void> confirmReturn(
            @PathVariable UUID id,
            String note,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.asm.delivery.security.UserPrincipal principal) {
        exceptionResolutionService.confirmReturn(id, note, principal);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a delivery", description = "Cancels a delivery that has not yet entered transit. Returns 400 if the delivery is already IN_TRANSIT or beyond. The order is reverted to PENDING for potential re-import.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery cancelled"),
        @ApiResponse(responseCode = "400", description = "Cannot cancel — delivery already in transit or completed", content = @Content)
    })
    @IdempotentOperation
    public ResponseEntity<Void> cancel(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Cancellation reason (optional)", example = "Customer request") String reason) {
        exceptionResolutionService.cancelDelivery(id, reason);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/create-backorder")
    @Operation(summary = "Create backorder delivery", description = "Creates a new UNSCHEDULED delivery for the items that were not delivered in a partial delivery. Only valid for deliveries with status PARTIAL.")
    @IdempotentOperation
    public ResponseEntity<AdminDeliveryDetailResponse> createBackorder(@PathVariable UUID id) {
        return ResponseEntity.ok(exceptionResolutionService.createBackorderDelivery(id));
    }

    @GetMapping("/drivers")
    @Operation(summary = "List drivers with availability and active deliveries")
    public ResponseEntity<List<AdminDriverResponse>> drivers() {
        return ResponseEntity.ok(dispatchService.getDrivers());
    }

    @GetMapping("/stats")
    @Operation(summary = "Aggregated delivery stats", description = "Returns per-driver delivery counts, failure rates, and COD totals for the requested period.")
    public ResponseEntity<AdminStatsResponse> stats(
            @Parameter(description = "Time period", schema = @Schema(allowableValues = {"day", "week", "month", "year"}), example = "day")
            @RequestParam(required = false, defaultValue = "day") String period,
            @Parameter(description = "Start date (overrides period)", example = "2026-05-01")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (overrides period)", example = "2026-05-13")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getStats(period, from, to));
    }

    @GetMapping("/ops-overview")
    @Operation(summary = "Operations-ready overview: SLA, dispatch lanes, and exceptions")
    public ResponseEntity<AdminOpsOverviewResponse> opsOverview(
            @RequestParam(required = false, defaultValue = "day") String period,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Integer waitingSlaMinutes,
            Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsOverview(period, from, to, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Get delivery status history", description = "Returns every status change for this delivery — who changed it, when, and any note attached. Ordered chronologically.")
    public ResponseEntity<List<StatusHistoryResponse>> history(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID id) {
        return ResponseEntity.ok(dispatchService.getDeliveryHistory(id));
    }

    @GetMapping("/{id}/pod")
    @Operation(summary = "Get proof of delivery", description = "Returns the POD submitted by the driver: recipient name, signature image, photos, and timestamp. Only available for DELIVERED deliveries.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "POD data"),
        @ApiResponse(responseCode = "404", description = "Delivery not found or POD not yet submitted", content = @Content)
    })
    public ResponseEntity<ProofOfDeliveryResponse> getPod(
            @Parameter(description = "Delivery UUID", required = true) @PathVariable UUID id) {
        return ResponseEntity.ok(podService.getPodAdmin(id));
    }

    @GetMapping("/{id}/bon-livraison")
    @Operation(
        summary = "Download bon de livraison PDF",
        description = """
            Generates and returns the delivery note (bon de livraison) as a PDF.
            Includes: company logo, client info, delivery address, product list with quantities and prices,
            driver and barcode.
            """
    )
    @ApiResponse(responseCode = "200", description = "PDF file (application/pdf)")
    public ResponseEntity<byte[]> bonLivraison(@PathVariable UUID id) {
        byte[] pdf = bonLivraisonPdfService.generate(id);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=bon-" + id + ".pdf")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "Cancel order and sync to ERP", description = "Cancels the order and its associated delivery. Triggers an asynchronous ERP sync to cancel the corresponding Odoo sale order. If the ERP is unavailable, the sync is retried with exponential backoff.")
    @IdempotentOperation
    public ResponseEntity<Void> adminCancelOrder(
            @PathVariable UUID orderId,
            String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        orderService.adminCancelOrder(orderId, principal, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sync-zones")
    @Operation(summary = "Recalculate delivery zones", description = "Iterates all deliveries and re-assigns zones based on current zone polygon definitions. Run this after modifying zone boundaries.")
    public ResponseEntity<Void> syncZones() {
        dispatchService.syncAllZones();
        return ResponseEntity.noContent().build();
    }
}
