package com.asm.delivery.service.analytics.filter;

import com.asm.delivery.entity.DeliveryStatus;
import jakarta.persistence.Query;

import java.util.UUID;

/**
 * Reusable, server-side scope filter for analytics queries — the "who/where/what" dimensions that
 * narrow a delivery set (the time dimension is handled separately by {@link PeriodResolver}).
 *
 * <p>Designed for the dynamic JPQL builders in the analytics service: each filtered query splices
 * {@link #jpql()} into its {@code WHERE} and calls {@link #bind(Query)} afterwards. The fragment
 * assumes the {@code Delivery} alias is {@code d}; zone uses the implicit-join path
 * {@code d.order.zoneId} so no explicit {@code JOIN} is required.
 *
 * <p>All fields are optional — a null field contributes no clause and binds no parameter, so
 * {@link #NONE} is a true no-op that leaves existing queries unchanged.
 *
 * @param driverId narrow to one driver, or null
 * @param zoneId   narrow to one zone (resolved from name upstream), or null
 * @param status   narrow to one delivery status, or null
 * @param motif    narrow to one failure code, or null/blank
 */
public record AnalyticsFilter(UUID driverId, UUID zoneId, DeliveryStatus status, String motif) {

    public static final AnalyticsFilter NONE = new AnalyticsFilter(null, null, null, null);

    /** Scope filter carrying only the dashboard pivots that don't fight per-status/per-motif breakdowns. */
    public static AnalyticsFilter scope(UUID driverId, UUID zoneId) {
        return new AnalyticsFilter(driverId, zoneId, null, null);
    }

    public boolean isEmpty() {
        return driverId == null && zoneId == null && status == null
                && (motif == null || motif.isBlank());
    }

    /** JPQL {@code AND ...} fragment for the {@code Delivery d} alias; empty when no filter is set. */
    public String jpql() {
        StringBuilder sb = new StringBuilder();
        if (driverId != null) sb.append(" AND d.driverId = :fDriverId");
        if (zoneId != null) sb.append(" AND d.order.zoneId = :fZoneId");
        if (status != null) sb.append(" AND d.status = :fStatus");
        if (motif != null && !motif.isBlank()) sb.append(" AND d.failureCode = :fMotif");
        return sb.toString();
    }

    /**
     * Native-SQL {@code AND ...} fragment, assuming {@code deliveries d} joined to {@code orders o}.
     * Status/motif bind as strings (enum name) for native queries.
     */
    public String nativeSql() {
        StringBuilder sb = new StringBuilder();
        if (driverId != null) sb.append(" AND d.driver_id = :fDriverId");
        if (zoneId != null) sb.append(" AND o.zone_id = :fZoneId");
        if (status != null) sb.append(" AND d.status = :fStatusName");
        if (motif != null && !motif.isBlank()) sb.append(" AND d.failure_code = :fMotif");
        return sb.toString();
    }

    /** Bind whichever named parameters {@link #jpql()} referenced. Safe to call on any query. */
    public void bind(Query query) {
        if (driverId != null) query.setParameter("fDriverId", driverId);
        if (zoneId != null) query.setParameter("fZoneId", zoneId);
        if (status != null) query.setParameter("fStatus", status);
        if (motif != null && !motif.isBlank()) query.setParameter("fMotif", motif);
    }

    /** Bind for {@link #nativeSql()} (status bound by name, UUIDs as-is). */
    public void bindNative(Query query) {
        if (driverId != null) query.setParameter("fDriverId", driverId);
        if (zoneId != null) query.setParameter("fZoneId", zoneId);
        if (status != null) query.setParameter("fStatusName", status.name());
        if (motif != null && !motif.isBlank()) query.setParameter("fMotif", motif);
    }
}
