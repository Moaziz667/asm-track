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
@Tag(name = "Admin Deliveries", description = "Administrative delivery monitoring APIs")
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

    @GetMapping
    @Operation(summary = "Recherche multicritères et monitoring des livraisons",
               description = "Permet de filtrer par statut (LATE/ON_TIME), par chauffeur, par date de livraison ou par zone d'activité.")
    public ResponseEntity<Page<AdminDeliverySummaryResponse>> list(
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestParam(required = false) OrderSource source,
            @RequestParam(required = false) UUID zoneId,
            @RequestParam(required = false) Boolean unpinned,
            @ParameterObject Pageable pageable
    ) {
        return ResponseEntity.ok(dispatchService.searchDeliveries(status, driverId, date, source, zoneId, unpinned, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get delivery detail including order and history")
    public ResponseEntity<AdminDeliveryDetailResponse> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(dispatchService.getDeliveryDetail(id));
    }

    @GetMapping("/{id}/geocode")
    @Operation(summary = "Attempt Nominatim geocoding of the delivery dropoff address (always show map for confirmation)")
    public ResponseEntity<GeocodeSuggestionResponse> geocode(@PathVariable UUID id) {
        AdminDeliveryDetailResponse delivery = dispatchService.getDeliveryDetail(id);
        String query = buildGeocodeQuery(delivery.getDropoffAddress(), delivery.getDropoffCity());
        return ResponseEntity.ok(geocodingService.geocode(query));
    }

    @GetMapping("/reverse-geocode")
    @Operation(summary = "Reverse geocode a lat/lng pin to get address, city, postal code")
    public ResponseEntity<GeocodeSuggestionResponse> reverseGeocode(
            @RequestParam double lat,
            @RequestParam double lng) {
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
    @Operation(summary = "Assign a waiting delivery to a driver")
    @IdempotentOperation
    public ResponseEntity<AdminDeliveryDetailResponse> assign(
            @PathVariable UUID id,
            @Valid @RequestBody AssignDeliveryRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(dispatchService.assignDelivery(id, request, principal));
    }

    @PostMapping("/{id}/pin-dropoff")
    @Operation(summary = "Manually pin delivery dropoff coordinates and optional normalized address")
    @IdempotentOperation
    public ResponseEntity<AdminDeliveryDetailResponse> pinDropoff(
            @PathVariable UUID id,
            @Valid @RequestBody PinDropoffRequest request) {
        return ResponseEntity.ok(dispatchService.pinDropoff(id, request));
    }

    @PostMapping("/{id}/confirm-return")
    @Operation(summary = "Confirm that a return-to-origin parcel has been physically received at depot. Clears the flag and unblocks re-dispatch.")
    public ResponseEntity<Void> confirmReturn(
            @PathVariable UUID id,
            @RequestParam(required = false) String note,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.asm.delivery.security.UserPrincipal principal) {
        exceptionResolutionService.confirmReturn(id, note, principal);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a delivery before it is in transit. Deletes the delivery and reverts order to PENDING.")
    @IdempotentOperation
    public ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        exceptionResolutionService.cancelDelivery(id, reason);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/create-backorder")
    @Operation(summary = "Create a new Delivery task for the backordered items")
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
    @Operation(summary = "Aggregated stats for today, per driver and failures")
    public ResponseEntity<AdminStatsResponse> stats(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getStats(period, from, to));
    }

    @GetMapping("/ops-overview")
    @Operation(summary = "Operations-ready overview: SLA, dispatch lanes, and exceptions")
    public ResponseEntity<AdminOpsOverviewResponse> opsOverview(
            @RequestParam(required = false, defaultValue = "day") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer waitingSlaMinutes,
            @RequestParam(required = false) Integer transitSlaMinutes
    ) {
        return ResponseEntity.ok(opsAnalyticsService.getOpsOverview(period, from, to, waitingSlaMinutes, transitSlaMinutes));
    }

    @GetMapping("/{id}/pod")
    @Operation(summary = "Get proof of delivery for auditing")
    public ResponseEntity<ProofOfDeliveryResponse> getPod(@PathVariable UUID id) {
        return ResponseEntity.ok(podService.getPodAdmin(id));
    }

    @GetMapping("/{id}/bon-livraison")
    @Operation(summary = "Download bon de livraison PDF for a delivery")
    public ResponseEntity<byte[]> bonLivraison(@PathVariable UUID id) {
        byte[] pdf = bonLivraisonPdfService.generate(id);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=bon-" + id + ".pdf")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "Admin cancel order — delivery reverts to CANCELLED, Odoo sync triggered")
    public ResponseEntity<Void> adminCancelOrder(
            @PathVariable UUID orderId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        orderService.adminCancelOrder(orderId, principal, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sync-zones")
    @Operation(summary = "Recalculate zones for all deliveries based on current zone definitions")
    public ResponseEntity<Void> syncZones() {
        dispatchService.syncAllZones();
        return ResponseEntity.noContent().build();
    }
}
