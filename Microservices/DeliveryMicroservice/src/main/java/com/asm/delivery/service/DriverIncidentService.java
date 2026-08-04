package com.asm.delivery.service;

import com.asm.delivery.dto.request.IncidentReportRequest;
import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.storage.MinioStorageService;
import com.asm.delivery.storage.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a driver does when a delivery does not go through: fail it, hand it back, or report a problem.
 *
 * <p>Grouped by the moment they belong to rather than by technical kind. All three are exits from the
 * journey, all three end in an ERP consequence or an operator's inbox, and none of them produces the
 * proof-of-delivery machinery the completion path needs — which is why they moved out of the service
 * that carries it.
 *
 * <p>Ownership and history stay in {@link DeliveryTransitionSupport}, shared with every other phase:
 * a driver may only fail or abandon a delivery he is actually holding.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DriverIncidentService {

    private final DeliveryRepository        deliveryRepo;
    private final DeliveryReportRepository  reportRepo;
    private final EventPublisher            eventPublisher;
    private final MinioStorageService       minioStorageService;
    private final AuditLogService           auditLogService;
    private final OutboxProcessor           outboxProcessor;
    private final FailureReasonService      failureReasonService;
    private final com.asm.delivery.service.route.RouteExecutionService routeExecutionService;
    private final com.asm.delivery.sla.SlaStateService slaStateService;
    private final DriverDeliveryMapper      mapper;
    private final DeliveryTransitionSupport transitions;

    /*
      Lazy, exactly as in the service this was split out of. Both of these sit on a construction-time
      cycle — ExceptionResolutionService reaches DispatchService, which reaches back here — and the
      original broke it this way deliberately. Turning them into constructor arguments during the
      split re-created the cycle and stopped the whole Spring context from starting; the driver
      journey tests caught it.
    */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private com.asm.delivery.service.dispatch.ExceptionResolutionService exceptionResolutionService;

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private RmaService rmaService;

    // ── Fail ──────────────────────────────────────────────────────────────────

    @Transactional
    public DriverDeliveryResponse fail(UUID deliveryId, UUID driverId, String failureReasonCode,
                                       FailureCode legacyCode, String failureComment, UserPrincipal principal) {
        Delivery delivery = transitions.loadAndAuthorize(deliveryId, driverId);

        // ADR-033 — A return collection SHARES the forward order, a completed+synced transaction. A failed
        // collection must NEVER touch the forward order's ERP state (no PENDING_SYNC, no ERP failure sync,
        // no replacement shipment — nothing exists in Odoo for the return until RESTOCKED). Its own RMA is
        // closed instead so a fresh return can be raised.
        boolean isReturnPickup = delivery.getKind() == com.asm.delivery.entity.DeliveryKind.RETURN_PICKUP;

        // A forward delivery can only fail once in the field (PICKED_UP/IN_TRANSIT). A return collection can
        // ALSO fail from SCHEDULED — the classic "client absent / colis pas prêt" happens BEFORE the driver
        // ever gets the parcel in hand (there is no depot-load step for a reverse leg). ADR-033.
        boolean failable = delivery.getStatus() == DeliveryStatus.PICKED_UP
                || delivery.getStatus() == DeliveryStatus.IN_TRANSIT
                || (isReturnPickup && delivery.getStatus() == DeliveryStatus.SCHEDULED);
        if (!failable) {
            throw AppException.badRequest(isReturnPickup
                    ? "Can only fail a return collection from SCHEDULED, PICKED_UP or IN_TRANSIT state"
                    : "Can only fail delivery from PICKED_UP or IN_TRANSIT state");
        }

        // Resolve the configurable reason → analytics category + human label.
        final FailureCode failureCode;
        final String reasonLabel;
        if (failureReasonCode != null && !failureReasonCode.isBlank()) {
            FailureReasonService.Resolved resolved = failureReasonService.resolve(failureReasonCode);
            failureCode = resolved.category();
            reasonLabel = resolved.label();
        } else {
            failureCode = legacyCode != null ? legacyCode : FailureCode.OTHER;
            reasonLabel = failureCode.name();
        }
        // Persist a human-readable reason: label enriched with the free-text comment when present.
        String storedReason = (failureComment != null && !failureComment.isBlank())
                ? reasonLabel + " — " + failureComment.trim()
                : reasonLabel;

        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setFailReason(storedReason);
        // Keep the driver's words on their own field too, so the admin motif label and the driver
        // comment can be shown separately (fail_reason stays flattened for ERP/analytics/tracking).
        delivery.setFailureComment(failureComment != null && !failureComment.isBlank() ? failureComment.trim() : null);
        delivery.setFailureCode(failureCode);
        // B5 — A forward failure is pushed to the ERP, so the order must be PENDING_SYNC for the
        // reconciliation sweep to recover it if the ERP result is ever lost. Return collections skip this.
        if (!isReturnPickup && delivery.getOrder() != null) {
            delivery.getOrder().setErpSyncStatus("PENDING_SYNC");
        }
        delivery = deliveryRepo.save(delivery);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_FAIL", "DELIVERY", delivery.getId().toString(),
            Map.of("driver", driverName, "client", clientName, "code", String.valueOf(failureCode),
                   "reason", failureComment != null ? failureComment : "", "action", "DELIVERY_FAILED"));

        // Release driver + increment stat (best-effort)
        Map<String, Object> failedPayload = new HashMap<>();
        failedPayload.put("driverId", driverId.toString());
        failedPayload.put("stat", "failed");
        outboxProcessor.enqueue("INCREMENT_DRIVER_STAT", failedPayload);

        transitions.appendHistory(delivery, DeliveryStatus.FAILED, driverId.toString(), Role.DRIVER, "DELIVERY_FAILED",
                Map.of("driverId", driverId.toString(), "reason", failureComment != null ? failureComment : "", "code", failureCode != null ? failureCode.name() : ""));
        routeExecutionService.syncStopFromDelivery(delivery.getId(), DeliveryStatus.FAILED, delivery.getFailedAt(), failureComment);
        slaStateService.refresh(delivery);
        eventPublisher.publishDeliveryFailed(delivery.getOrder(), delivery, failureComment);

        // P1: Outbox Sync for failures — forward-only (see ADR-033 note above).
        if (!isReturnPickup) {
            outboxProcessor.enqueue("ERP_SYNC_FAILURE", Map.of(
                "deliveryId", deliveryId.toString(),
                "failureCode", failureCode != null ? failureCode.name() : "GENERAL",
                "comment", failureComment != null ? failureComment : ""
            ));

            // Disposition-code re-delivery: if this failed visit was a refusal for a DEFECT (damaged /
            // wrong item / postponed), the customer still wants the product — create a replacement shipment
            // to re-deliver a good unit. No-op for a plain failure (client absent, outright refusal).
            if (delivery.getOrder() != null) {
                exceptionResolutionService.createReplacementShipment(delivery.getOrder().getId(), deliveryId);
            }
        } else if (delivery.getRmaId() != null) {
            // ADR-033 — a failed collection closes its RMA (terminal) so the one-open-return guard is freed
            // and a fresh return can be raised. No Odoo touch — nothing was created there yet.
            rmaService.onReturnCollectionFailed(delivery.getRmaId(), storedReason);
        }

        return mapper.toDriverDeliveryResponse(delivery);
    }

    // ── Cancel (driver cancels → back to UNSCHEDULED) ─────────────────────

    @Transactional
    public DriverDeliveryResponse cancelByDriver(UUID deliveryId, UUID driverId, String reason, UserPrincipal principal) {
        Delivery delivery = transitions.loadAndAuthorize(deliveryId, driverId);

        if (delivery.getStatus() != DeliveryStatus.SCHEDULED) {
            throw AppException.badRequest("Driver can only cancel from SCHEDULED state");
        }

        delivery.setStatus(DeliveryStatus.UNSCHEDULED);
        delivery.setDriverId(null);
        delivery.setAssignedAt(null);
        delivery.setWaitingSlaMinutes(null);
        delivery.setAssignSlaMinutes(null);
        delivery.setPickupSlaMinutes(null);
        delivery = deliveryRepo.save(delivery);

        // Release driver + increment stat (best-effort)
        Map<String, Object> cancelPayload = new HashMap<>();
        cancelPayload.put("driverId", driverId.toString());
        cancelPayload.put("stat", "cancelled");
        outboxProcessor.enqueue("INCREMENT_DRIVER_STAT", cancelPayload);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "N/A";
        auditLogService.logAction(principal, "DRIVER_CANCEL", "DELIVERY", delivery.getId().toString(),
                Map.of("chauffeur", driverName, "client", clientName, "motif", StringUtils.hasText(reason) ? reason : "aucun",
                       "action", "Annulation par le chauffeur"));
        transitions.appendHistory(delivery, DeliveryStatus.UNSCHEDULED, driverId.toString(), Role.DRIVER,
                "DELIVERY_CANCELLED_BY_DRIVER",
                Map.of("driverId", driverId.toString(), "reason", StringUtils.hasText(reason) ? reason : ""));

        eventPublisher.publishDeliveryCancelled(delivery.getOrder(), delivery, driverId);



        return mapper.toDriverDeliveryResponse(delivery);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    @Transactional
    public void report(UUID deliveryId, UUID driverId, ReportType reportType, String description, UserPrincipal principal) {
        Delivery delivery = transitions.loadAndAuthorize(deliveryId, driverId);

        reportRepo.save(DeliveryReport.builder()
                .deliveryId(delivery.getId())
                .driverId(driverId)
                .reportType(reportType)
                .description(description)
                .build());

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        auditLogService.logAction(principal, "DRIVER_REPORT", "DELIVERY", deliveryId.toString(),
                Map.of("chauffeur", driverName, "type", String.valueOf(reportType), "details", description != null ? description : "",
                       "action", "Signalement soumis"));
    }

    @Transactional
    public void reportIncident(UUID driverId, IncidentReportRequest req, UserPrincipal principal) {
        java.util.List<String> photoUrls = new java.util.ArrayList<>();
        
        if (req.getPhotosBase64() != null) {
            for (int i = 0; i < req.getPhotosBase64().size(); i++) {
                String objectPath = "reports/" + driverId + "/" + System.currentTimeMillis() + "-" + i + ".png";
                photoUrls.add(minioStorageService.uploadBase64(req.getPhotosBase64().get(i), objectPath));
            }
        }

        DeliveryReport report = DeliveryReport.builder()
                .deliveryId(req.getDeliveryId())
                .driverId(driverId)
                .reportType(req.getReportType())
                .description(req.getDescription())
                .lat(req.getLat())
                .lng(req.getLng())
                .photoUrls(photoUrls)
                .build();

        reportRepo.save(report);

        String targetId = req.getDeliveryId() != null ? req.getDeliveryId().toString() : "GENERAL";
        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        
        auditLogService.logAction(principal, "REPORT_INCIDENT", "INCIDENT", targetId,
                java.util.Map.of(
                    "chauffeur", driverName,
                    "type", req.getReportType().name(), 
                    "photos", photoUrls.size(), 
                    "action", "Signalement d'incident pro"
                ));
    }
}
