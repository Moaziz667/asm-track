package com.asm.delivery.controller;

import com.asm.delivery.dto.request.CancelDeliveryRequest;
import com.asm.delivery.dto.request.FailDeliveryRequest;
import com.asm.delivery.dto.request.LocationUpdateRequest;
import com.asm.delivery.dto.request.ReportRequest;
import com.asm.delivery.dto.request.IncidentReportRequest;
import com.asm.delivery.dto.request.ProofOfDeliveryRequest;
import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.dto.response.MessageResponse;
import com.asm.delivery.dto.response.HandoffTokenResponse;
import com.asm.delivery.dto.request.HandoffConfirmRequest;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.BonLivraisonPdfService;
import com.asm.delivery.idempotency.IdempotentOperation;
import com.asm.delivery.service.DriverDeliveryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/driver/deliveries")
@Tag(name = "Driver Deliveries", description = "Delivery management for driver app")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class DriverDeliveryController {

    private final DriverDeliveryService deliveryService;
    private final BonLivraisonPdfService bonLivraisonPdfService;
    private final com.asm.delivery.service.FailureReasonService failureReasonService;

    @GetMapping("/available")
    @Operation(summary = "Get all deliveries waiting for a driver in the driver's city")
    public ResponseEntity<List<DriverDeliveryResponse>> getAvailable(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.getAvailable(UUID.fromString(principal.getUserId())));
    }

    @GetMapping("/active")
    @Operation(summary = "Get the driver's currently active delivery")
    public ResponseEntity<List<DriverDeliveryResponse>> getActive(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.getActive(UUID.fromString(principal.getUserId())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a specific delivery")
    public ResponseEntity<DriverDeliveryResponse> getDelivery(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.getDelivery(id, UUID.fromString(principal.getUserId())));
    }

    @PostMapping("/location")
    @Operation(summary = "Update driver location and record tracking for active delivery")
    public ResponseEntity<MessageResponse> updateLocation(
            @Valid @RequestBody LocationUpdateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        deliveryService.updateLocation(UUID.fromString(principal.getUserId()), req.getLat(), req.getLng());        
        return ResponseEntity.ok(new MessageResponse("Location updated"));
    }

    @PostMapping("/{id}/accept")
    @IdempotentOperation
    @Operation(summary = "Accept a delivery (atomic — 409 if taken)")
    public ResponseEntity<DriverDeliveryResponse> accept(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.accept(id, UUID.fromString(principal.getUserId()), principal));
    }

    @PostMapping("/{id}/pickup")
    @IdempotentOperation
    @Operation(summary = "Confirm package pickup")
    public ResponseEntity<DriverDeliveryResponse> pickup(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.pickup(id, UUID.fromString(principal.getUserId()), principal));
    }

    @PostMapping("/{id}/transit")
    @IdempotentOperation
    @Operation(summary = "Start transit to delivery address")
    public ResponseEntity<DriverDeliveryResponse> transit(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) LocationUpdateRequest locationReq,
            @AuthenticationPrincipal UserPrincipal principal) {
        java.math.BigDecimal lat = locationReq != null ? locationReq.getLat() : null;
        java.math.BigDecimal lng = locationReq != null ? locationReq.getLng() : null;
        return ResponseEntity.ok(deliveryService.transit(id, UUID.fromString(principal.getUserId()), lat, lng, principal));
    }

    @PostMapping("/{id}/complete")
    @IdempotentOperation
    @Operation(summary = "Mark delivery as completed", description = "@Deprecated: Use /pod endpoint instead. Still works for backward compatibility.")
    public ResponseEntity<DriverDeliveryResponse> complete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.complete(id, UUID.fromString(principal.getUserId()), principal));
    }

    @PostMapping("/{id}/fail")
    @IdempotentOperation
    @Operation(summary = "Mark delivery as failed (from PICKED_UP or IN_TRANSIT)")
    public ResponseEntity<DriverDeliveryResponse> fail(
            @PathVariable UUID id,
            @Valid @RequestBody FailDeliveryRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.fail(
            id,
            UUID.fromString(principal.getUserId()),
            req.getFailureReasonCode(),
            req.getFailureCode(),
            req.getFailureComment(),
            principal
        ));
    }

    @GetMapping("/failure-reasons")
    @Operation(summary = "List active failure reasons for the failure form")
    public ResponseEntity<List<com.asm.delivery.dto.response.FailureReasonResponse>> failureReasons() {
        return ResponseEntity.ok(failureReasonService.listActive());
    }

    @PostMapping("/{id}/cancel")
    @IdempotentOperation
    @Operation(summary = "Cancel delivery (driver) — resets to WAITING_DRIVER")
    public ResponseEntity<DriverDeliveryResponse> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelDeliveryRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        String reason = req != null ? req.getReason() : null;
        return ResponseEntity.ok(deliveryService.cancelByDriver(id, UUID.fromString(principal.getUserId()), reason, principal));
    }

    @PostMapping("/{id}/report")
    @Operation(summary = "Submit a report for a delivery")
    public ResponseEntity<MessageResponse> report(
            @PathVariable UUID id,
            @Valid @RequestBody ReportRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        deliveryService.report(id, UUID.fromString(principal.getUserId()), req.getReportType(), req.getDescription(), principal);
        return ResponseEntity.ok(new MessageResponse("Report submitted"));
    }

    @PostMapping("/report-incident")
    @Operation(summary = "Submit a professional incident report (multi-photo, GPS, general)")
    public ResponseEntity<MessageResponse> reportIncident(
            @Valid @RequestBody IncidentReportRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        deliveryService.reportIncident(UUID.fromString(principal.getUserId()), req, principal);
        return ResponseEntity.ok(new MessageResponse("Incident report submitted"));
    }

    @PostMapping("/{id}/pod")
    @IdempotentOperation
    @Operation(summary = "Submit proof of delivery (POD)", description = "DRIVER only. Delivery must be IN_TRANSIT. Saves POD, completes delivery, triggers Odoo sync. Returns DELIVERED status.")
    public ResponseEntity<DriverDeliveryResponse> submitPod(
            @PathVariable UUID id,
            @Valid @RequestBody ProofOfDeliveryRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.submitPod(id, UUID.fromString(principal.getUserId()), req, principal));
    }

    @GetMapping("/{id}/handoff-token")
    @Operation(summary = "Generate a secure token for handoff", description = "DRIVER only. Called by Driver A to show a QR code.")
    public ResponseEntity<HandoffTokenResponse> generateHandoffToken(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.generateHandoffToken(id, UUID.fromString(principal.getUserId())));
    }

    @PostMapping("/{id}/handoff")
    @Operation(summary = "Confirm handoff receipt with token", description = "DRIVER only. Called by Driver B to confirm physical receipt of a package using Driver A's token.")
    public ResponseEntity<DriverDeliveryResponse> confirmHandoff(
            @PathVariable UUID id,
            @Valid @RequestBody HandoffConfirmRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deliveryService.confirmHandoff(id, UUID.fromString(principal.getUserId()),
                req.getToken(), req.getLat(), req.getLng(), req.getNotes(), principal));
    }

    @GetMapping("/{id}/bon-livraison")
    @Operation(summary = "Download bon de livraison PDF for a delivery")
    public ResponseEntity<byte[]> bonLivraison(@PathVariable UUID id) {
        byte[] pdf = bonLivraisonPdfService.generate(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=bon-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
