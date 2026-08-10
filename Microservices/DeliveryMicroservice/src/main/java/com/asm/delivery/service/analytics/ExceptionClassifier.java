package com.asm.delivery.service.analytics;

import com.asm.delivery.dto.response.AdminDeliverySummaryResponse;
import com.asm.delivery.dto.response.AdminOpsExceptionsResponse;
import com.asm.delivery.dto.response.AdminOpsOverviewResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.service.SystemSettingsService;
import com.asm.delivery.transport.DriverDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a delivery's live state into an operational exception: its severity (CRITICAL/WARNING/INFO),
 * a stable {@code motif} key, and a human comment, anchored on the configured SLA thresholds. Owns
 * both the dashboard "needs-attention" rows ({@link AdminOpsOverviewResponse.ExceptionRow}) and the
 * exceptions-page items ({@link AdminOpsExceptionsResponse.ExceptionItem}). Extracted from
 * OpsAnalyticsService so the classification rulebook is one cohesive unit, separate from KPI stats.
 */
@Component
@RequiredArgsConstructor
public class ExceptionClassifier {

    private final SystemSettingsService systemSettingsService;

    @Value("${ops.sla.waiting-limit-minutes:15}")
    private int waitingLimitMinutes;

    @Value("${ops.sla.assign-limit-minutes:20}")
    private int assignLimitMinutes;

    @Value("${ops.sla.waiting-minutes:15}")
    private int waitingSlaMinutes;

    /**
     * Dashboard needs-attention row, or null when the delivery does not currently need dispatcher action.
     * Severity scale is aligned with the exceptions page ({@link #classifyException}) so the two views
     * never disagree: FAILED / late IN_TRANSIT / past-due UNSCHEDULED = CRITICAL; PARTIAL, late SCHEDULED,
     * stuck PICKED_UP, same-day UNSCHEDULED = WARNING. CANCELLED is terminal (no action) → never surfaced.
     */
    public AdminOpsOverviewResponse.ExceptionRow toExceptionRow(AdminDeliverySummaryResponse s,
                                                                LocalDateTime now,
                                                                int effectiveWaitingSlaMinutes,
                                                                int effectiveTransitSlaMinutes) {
        if ("FAILED".equals(s.getStatus())) {
            return buildExceptionRow(s, DeliveryStatus.FAILED, "CRITICAL", "Delivery failed: manual intervention required");
        }
        // CANCELLED is terminal — no action required — so it is intentionally NOT a needs-attention row.
        if ("PARTIALLY_DELIVERED".equals(s.getStatus())) {
            return buildExceptionRow(s, DeliveryStatus.PARTIALLY_DELIVERED, "WARNING", "Partial delivery reported");
        }
        if ("UNSCHEDULED".equals(s.getStatus())) {
            // Same rule as the exceptions page: severity by scheduled date. Future schedules aren't
            // actionable yet, so they're dropped from needs-attention.
            if (s.getScheduledAt() != null) {
                LocalDate scheduledDate = s.getScheduledAt().toLocalDate();
                LocalDate today = now.toLocalDate();
                if (scheduledDate.isBefore(today)) {
                    return buildExceptionRow(s, DeliveryStatus.UNSCHEDULED, "CRITICAL",
                         "En retard (Planifié le " + scheduledDate + ")");
                }
                if (scheduledDate.isEqual(today)) {
                    return buildExceptionRow(s, DeliveryStatus.UNSCHEDULED, "WARNING", "Planifié pour aujourd'hui");
                }
                return null;
            }
            if (s.getCreatedAt() != null) {
                int waitingLimit = systemSettingsService.getInt("ops.sla.waiting-limit-minutes", waitingLimitMinutes);
                long elapsed = Duration.between(s.getCreatedAt(), now).toMinutes();
                if (elapsed > waitingLimit) {
                    return buildExceptionRow(s, DeliveryStatus.UNSCHEDULED, "WARNING",
                         String.format("Planning SLA exceeded: unscheduled for %d minutes", elapsed));
                }
            }
            return null;
        }
        if ("SCHEDULED".equals(s.getStatus())) {
            int assignLimit = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
            Long elapsed = resolveAssignSlaElapsedMinutes(s, now);
            if (elapsed != null && elapsed > assignLimit) {
                return buildExceptionRow(s, DeliveryStatus.SCHEDULED, "WARNING",
                     "Assignment SLA exceeded: driver delay in depot pickup");
            }
            return null;
        }
        if ("PICKED_UP".equals(s.getStatus())) {
            // Parcel loaded but transit not started past the pickup SLA — a genuine stuck state that the
            // dashboard previously missed (it was only on the exceptions page).
            if (s.getPickedUpAt() != null) {
                int pickupLimit = systemSettingsService.getInt("ops.sla.pickup-limit-minutes", 120);
                long elapsed = Duration.between(s.getPickedUpAt(), now).toMinutes();
                if (elapsed > pickupLimit) {
                    return buildExceptionRow(s, DeliveryStatus.PICKED_UP, "WARNING",
                         String.format("Picked up but transit not started for %d min", elapsed));
                }
            }
            return null;
        }
        if ("IN_TRANSIT".equals(s.getStatus())) {
            if (s.getRouteEndTimeWindow() != null) {
                LocalDateTime deadline = LocalDateTime.of(now.toLocalDate(), s.getRouteEndTimeWindow());
                if (now.isAfter(deadline)) {
                    return buildExceptionRow(s, DeliveryStatus.IN_TRANSIT, "CRITICAL", "Critical delay: delivery time window exceeded");
                }
            }
            return null;
        }
        return null;
    }

    private Long resolveAssignSlaElapsedMinutes(AdminDeliverySummaryResponse s, LocalDateTime now) {
        if (s.getRouteId() == null) {
            return s.getAssignedAt() != null
                            ? Duration.between(s.getAssignedAt(), now).toMinutes()
                            : null;
        }

        if (s.getRouteStartedAt() != null) {
            return Duration.between(s.getRouteStartedAt(), now).toMinutes();
        }
        if (s.getRouteDepartureTime() != null) {
            return Duration.between(s.getRouteDepartureTime(), now).toMinutes();
        }
        if (s.getRouteDate() != null && s.getRoutePlannedStartTime() != null) {
            return Duration.between(s.getRouteDate().atTime(s.getRoutePlannedStartTime()), now).toMinutes();
        }

        return s.getAssignedAt() != null
                        ? Duration.between(s.getAssignedAt(), now).toMinutes()
                        : null;
    }

    private AdminOpsOverviewResponse.ExceptionRow buildExceptionRow(AdminDeliverySummaryResponse s,
                                                                    DeliveryStatus status,
                                                                    String severity,
                                                                    String message) {
        return AdminOpsOverviewResponse.ExceptionRow.builder()
                        .deliveryId(s.getDeliveryId())
                        .orderId(s.getOrderId())
                        .orderRef(s.getOrderRef())
                        .status(status)
                        .clientName(s.getClientName())
                        .city(s.getDropoffCity())
                        .driverName(s.getDriverName())
                        .severity(severity)
                        .message(message)
                        .createdAt(s.getCreatedAt())
                        .scheduledAt(s.getScheduledAt())
                        .routeId(s.getRouteId())
                        .routeName(s.getRouteName())
                        .routeStatus(s.getRouteStatus())
                        .slaPhase(s.getSlaPhase())
                        .slaHealth(s.getSlaHealth())
                        .build();
    }

    /** Sort key for severity ordering: CRITICAL > WARNING > anything else. */
    public int severityScore(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) return 3;
        if ("WARNING".equalsIgnoreCase(severity)) return 2;
        return 1;
    }

    /** Exceptions-page item, or null when the delivery does not currently qualify as an exception. */
    public AdminOpsExceptionsResponse.ExceptionItem toExceptionItem(Delivery delivery,
                                                                    Map<String, DriverDTO> driverMap,
                                                                    Map<UUID, RouteInfo> routeInfoByDeliveryId,
                                                                    Map<UUID, String> zoneNameById,
                                                                    LocalDateTime now) {
        return toExceptionItem(delivery, driverMap, routeInfoByDeliveryId, zoneNameById, now, Map.of());
    }

    /**
     * @param latestOrderPerRef newest order created under each sale-order reference, used to retire
     *        an attempt whose follow-up already exists. Empty means "don't retire anything".
     */
    public AdminOpsExceptionsResponse.ExceptionItem toExceptionItem(Delivery delivery,
                                                                    Map<String, DriverDTO> driverMap,
                                                                    Map<UUID, RouteInfo> routeInfoByDeliveryId,
                                                                    Map<UUID, String> zoneNameById,
                                                                    LocalDateTime now,
                                                                    Map<String, LocalDateTime> latestOrderPerRef) {
        if (isSuperseded(delivery, latestOrderPerRef) || isAcknowledged(delivery)) {
            return null;
        }
        RouteInfo routeInfo = routeInfoByDeliveryId.get(delivery.getId());
        ExceptionClassification classification = classifyException(delivery, routeInfo, now);
        if (classification == null) {
            return null;
        }

        Order order = delivery.getOrder();
        String orderRef = order != null ? order.resolveRef() : "-";

        DriverDTO driver = delivery.getDriverId() != null ? driverMap.get(delivery.getDriverId().toString()) : null;

        return AdminOpsExceptionsResponse.ExceptionItem.builder()
                        .deliveryId(delivery.getId())
                        .orderId(order != null ? order.getId() : null)
                        .orderRef(orderRef)
                        .routeId(routeInfo != null ? routeInfo.routeId() : null)
                        .routeName(routeInfo != null ? routeInfo.routeName() : null)
                        .routeStatus(routeInfo != null && routeInfo.routeStatus() != null ? routeInfo.routeStatus().name() : null)
                        .status(delivery.getStatus())
                        .failureCode(delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null)
                        .motif(classification.motif())
                        .driverId(delivery.getDriverId())
                        .driverName(driver != null ? driver.getName() : null)
                        .clientName(order != null ? order.getClientName() : null)
                        .city(order != null ? order.getDropoffCity() : null)
                        .zoneName(order != null && order.getZoneId() != null ? zoneNameById.get(order.getZoneId()) : null)
                        .severity(classification.severity())
                        .comment(classification.comment())
                        .createdAt(delivery.getCreatedAt())
                        .updatedAt(delivery.getUpdatedAt())
                        .scheduledAt(order != null ? order.effectiveScheduledAt() : null)
                        .dropoffLat(order != null ? order.getDropoffLat() : null)
                        .dropoffLng(order != null ? order.getDropoffLng() : null)
                        .build();
    }

    /**
     * True when a dispatcher has declared this one handled, and nothing has happened since.
     *
     * <p>Deliberately not a permanent mute, and deliberately not compared against {@code updatedAt}
     * — recording the acknowledgement is itself an update, so that comparison would have revoked
     * every acknowledgement the instant it was made.
     *
     * <p>It is compared against the end of the attempt instead. A shipment that fails again moves
     * {@code failedAt} past the acknowledgement and comes straight back to the desk: what was closed
     * was one attempt, not the shipment for good. And the acknowledgement only applies to the two
     * states it can be granted in — reassigned, the delivery is a live one again and answers to the
     * ordinary rules, not to a silence granted over a previous failure.
     */
    private boolean isAcknowledged(Delivery delivery) {
        LocalDateTime ack = delivery.getOpsAcknowledgedAt();
        if (ack == null) return false;

        DeliveryStatus status = delivery.getStatus();
        if (status != DeliveryStatus.FAILED && status != DeliveryStatus.PARTIALLY_DELIVERED) return false;

        LocalDateTime endedAt = delivery.getCompletedAt() != null
                ? delivery.getCompletedAt() : delivery.getFailedAt();
        return endedAt != null && !endedAt.isAfter(ack);
    }

    /**
     * True when this attempt is over and its successor is already in the pipeline.
     *
     * <p>A partial delivery and a failure both stayed on the desk for good: nothing in the
     * classification could ever retire them, so the exception count could only climb, and a number
     * that only climbs stops being read. But the platform already knows when the work moved on — the
     * reliquat of a partial, or a re-attempt after a failure, is imported as its own order sharing
     * the sale-order reference.
     *
     * <p>The comparison is against the moment <em>this</em> attempt ended, which is what separates a
     * follow-up from a sibling: the shipments of a multi-depot order are all created before anyone
     * sets off, an order created after the van came back is a second try. Deliveries still in the
     * field are never retired — only a finished attempt can have a successor.
     */
    private boolean isSuperseded(Delivery delivery, Map<String, LocalDateTime> latestOrderPerRef) {
        if (latestOrderPerRef.isEmpty()) return false;
        DeliveryStatus status = delivery.getStatus();
        if (status != DeliveryStatus.PARTIALLY_DELIVERED && status != DeliveryStatus.FAILED) return false;

        Order order = delivery.getOrder();
        String ref = order != null ? order.getErpExternalRef() : null;
        if (!StringUtils.hasText(ref)) return false;

        LocalDateTime endedAt = delivery.getCompletedAt() != null ? delivery.getCompletedAt() : delivery.getFailedAt();
        if (endedAt == null) return false;      // no end recorded — say nothing rather than guess

        LocalDateTime newest = latestOrderPerRef.get(ref);
        return newest != null && newest.isAfter(endedAt);
    }

    private ExceptionClassification classifyException(Delivery delivery, RouteInfo routeInfo, LocalDateTime now) {
        DeliveryStatus status = delivery.getStatus();
        if (status == DeliveryStatus.FAILED) {
            String motif = delivery.getFailureCode() != null ? delivery.getFailureCode().name() : "FAILED";
            String comment = StringUtils.hasText(delivery.getFailReason()) ? delivery.getFailReason() : "Delivery failed and requires follow-up";
            return new ExceptionClassification("CRITICAL", motif, comment);
        }
        if (status == DeliveryStatus.SCHEDULED) {
            int effectiveAssignLimit = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
            LocalDateTime baseline = delivery.getAssignedAt();

            if (routeInfo != null) {
                // 1. Use Route reference if available
                baseline = routeInfo.startedAt();
                if (baseline == null) baseline = routeInfo.departureTime();
                if (baseline == null && routeInfo.date() != null && routeInfo.plannedStartTime() != null) {
                    baseline = routeInfo.date().atTime(routeInfo.plannedStartTime());
                }
            }

            if (baseline == null) {
                baseline = delivery.getAssignedAt() != null ? delivery.getAssignedAt() : delivery.getCreatedAt();
            }

            // We skip the complex previousStop logic here to avoid N+1 queries.
            // It was doing findByRouteIdOrderByStopOrderAsc in a loop!

            long elapsed = baseline != null ? Duration.between(baseline, now).toMinutes() : 0;
            String motif = elapsed > effectiveAssignLimit ? "SLA_SCHEDULED" : "SCHEDULED_MONITORING";
            String comment = elapsed > effectiveAssignLimit
                            ? "Pickup SLA exceeded: driver pickup delay"
                            : "Delivery unscheduled: awaiting routing";
            return new ExceptionClassification("WARNING", motif, comment);
        }
        if (status == DeliveryStatus.CANCELLED) {
            // Terminal: nobody can act on it, which is exactly what this class's own contract says a
            // hundred lines up — "CANCELLED is terminal (no action) → never surfaced". It was
            // surfaced, as CRITICAL no less, and the admin app quietly filtered it back out in the
            // browser. A rule enforced on the client is a rule the API does not have.
            return null;
        }
        if (status == DeliveryStatus.PARTIALLY_DELIVERED) {
            return new ExceptionClassification("WARNING", "PARTIAL_DELIVERY", "Partial delivery reported");
        }
        if (status == DeliveryStatus.UNSCHEDULED) {
            Order order = delivery.getOrder();
            if (order != null && order.effectiveScheduledAt() != null) {
                LocalDate scheduledDate = order.effectiveScheduledAt().toLocalDate();
                LocalDate today = now.toLocalDate();
                if (scheduledDate.isBefore(today)) {
                    return new ExceptionClassification("CRITICAL", "SLA_UNSCHEDULED_LATE", "En retard (Planifié le " + scheduledDate + ")");
                } else if (scheduledDate.isEqual(today)) {
                    return new ExceptionClassification("WARNING", "SLA_UNSCHEDULED_TODAY", "Planifié pour aujourd'hui");
                }
                return new ExceptionClassification("INFO", "UNSCHEDULED", "Awaiting planning");
            }
            LocalDateTime baseline = delivery.getUpdatedAt() != null
                    ? delivery.getUpdatedAt() : delivery.getCreatedAt();
            long elapsed = baseline != null ? Duration.between(baseline, now).toMinutes() : 0;
            if (elapsed > waitingSlaMinutes) {
                return new ExceptionClassification("WARNING", "SLA_UNSCHEDULED", "Planning SLA exceeded");
            }
            return new ExceptionClassification("INFO", "UNSCHEDULED", "Awaiting planning");
        }
        if (status == DeliveryStatus.IN_TRANSIT) {
            if (routeInfo != null && routeInfo.endTimeWindow() != null && routeInfo.date() != null) {
                LocalDateTime deadline = routeInfo.date().atTime(routeInfo.endTimeWindow());
                if (now.isAfter(deadline)) {
                    return new ExceptionClassification("CRITICAL", "SLA_IN_TRANSIT", "Delivery time window exceeded");
                }
            }
            return new ExceptionClassification("INFO", "IN_TRANSIT", "Delivery in transit");
        }
        if (status == DeliveryStatus.PICKED_UP) {
            long elapsed = delivery.getPickedUpAt() != null ? Duration.between(delivery.getPickedUpAt(), now).toMinutes() : 0;
            int effectivePickupLimit = systemSettingsService.getInt("ops.sla.pickup-limit-minutes", 120);
            if (elapsed > effectivePickupLimit) {
                return new ExceptionClassification("WARNING", "SLA_PICKUP", "Parcel picked up but transit not started for " + elapsed + " mins");
            }
            return new ExceptionClassification("INFO", "PICKED_UP", "Parcel loaded: awaiting transit departure");
        }
        return null;
    }

    private record ExceptionClassification(String severity, String motif, String comment) {}
}
