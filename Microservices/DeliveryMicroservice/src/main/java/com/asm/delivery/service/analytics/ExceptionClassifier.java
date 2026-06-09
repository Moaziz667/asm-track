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

    /** Dashboard needs-attention row, or null when the delivery is within SLA for its status. */
    public AdminOpsOverviewResponse.ExceptionRow toExceptionRow(AdminDeliverySummaryResponse s,
                                                                LocalDateTime now,
                                                                int effectiveWaitingSlaMinutes,
                                                                int effectiveTransitSlaMinutes) {
        if ("FAILED".equals(s.getStatus())) {
            return buildExceptionRow(s, DeliveryStatus.FAILED, "CRITICAL", "Delivery failed: manual intervention required");
        }
        if ("CANCELLED".equals(s.getStatus())) {
            return buildExceptionRow(s, DeliveryStatus.CANCELLED, "CRITICAL", "Delivery cancelled by user or system");
        }
        if ("PARTIALLY_DELIVERED".equals(s.getStatus())) {
            return buildExceptionRow(s, DeliveryStatus.PARTIALLY_DELIVERED, "WARNING", "Partial delivery reported");
        }
        if ("UNSCHEDULED".equals(s.getStatus())) {
            // Lead-time vs ERP scheduled date; fallback to since-creation when absent.
            if (s.getScheduledAt() != null) {
                int assignLeadTimeMins = systemSettingsService.getInt("ops.sla.assign-leadtime-minutes", 120);
                LocalDateTime deadline = s.getScheduledAt().minusMinutes(assignLeadTimeMins);
                if (now.isAfter(deadline)) {
                    long over = Duration.between(deadline, now).toMinutes();
                    return buildExceptionRow(s, DeliveryStatus.UNSCHEDULED, "WARNING",
                         String.format("Affectation tardive: %d min apres le seuil avant date planifiee", over));
                }
            } else if (s.getCreatedAt() != null) {
                int waitingLimit = systemSettingsService.getInt("ops.sla.waiting-limit-minutes", waitingLimitMinutes);
                long elapsed = Duration.between(s.getCreatedAt(), now).toMinutes();
                if (elapsed > waitingLimit) {
                    return buildExceptionRow(s, DeliveryStatus.UNSCHEDULED, "WARNING",
                         String.format("Planning SLA exceeded: unscheduled for %d minutes", elapsed));
                }
            }
        }
        if ("SCHEDULED".equals(s.getStatus())) {
            int assignLimit = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
            Long elapsed = resolveAssignSlaElapsedMinutes(s, now);
            if (elapsed == null) {
                return null;
            }
            if (elapsed > assignLimit) {
                return buildExceptionRow(s, DeliveryStatus.SCHEDULED, "CRITICAL",
                     "Assignment SLA exceeded: driver delay in depot pickup");
            }
        }
        if ("IN_TRANSIT".equals(s.getStatus())) {
            if (s.getRouteEndTimeWindow() != null) {
                LocalDateTime deadline = LocalDateTime.of(now.toLocalDate(), s.getRouteEndTimeWindow());
                if (now.isAfter(deadline)) {
                    return buildExceptionRow(s, DeliveryStatus.IN_TRANSIT, "CRITICAL", "Critical delay: delivery time window exceeded");
                }
            }
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
            String comment = StringUtils.hasText(delivery.getCancelReason()) ? delivery.getCancelReason() : "Delivery cancelled: manual review required";
            return new ExceptionClassification("CRITICAL", "CANCELLED", comment);
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
