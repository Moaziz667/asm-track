package com.asm.delivery.service.analytics;

import com.asm.delivery.entity.RouteStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Lightweight projection of the route a delivery sits on, pre-loaded in bulk to keep the analytics
 * and exception-classification paths free of per-delivery route lookups (the old N+1 hazard).
 * Shared between {@code OpsAnalyticsService} (stats / summaries) and {@link ExceptionClassifier}.
 */
public record RouteInfo(
        UUID routeId,
        String routeName,
        RouteStatus routeStatus,
        LocalDateTime startedAt,
        LocalDateTime departureTime,
        LocalDate date,
        LocalTime plannedStartTime,
        LocalTime endTimeWindow
) {}
