package com.asm.delivery.service.analytics.filter;

import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.OrderSource;
import jakarta.persistence.Query;

import java.util.UUID;

/**
 * Reusable, server-side scope filter for analytics queries — every "who/where/what" pivot that can
 * narrow a delivery set (the time dimension is handled separately by {@link PeriodResolver}).
 *
 * <p>Designed for the dynamic JPQL/native builders in the analytics services: each filtered query
 * splices {@link #jpql()} (or {@link #nativeSql()}) into its {@code WHERE} and calls the matching
 * bind. The JPQL fragment assumes the {@code Delivery} alias is {@code d}; zone/city/source use the
 * implicit-join path {@code d.order.*}; depot uses an {@code EXISTS} sub-query over the route stops.
 *
 * <p>All fields optional — a null field contributes no clause and binds no parameter, so
 * {@link #NONE} is a true no-op.
 *
 * @param driverId narrow to one driver
 * @param zoneId   narrow to one zone (resolved from name upstream)
 * @param status   narrow to one delivery status
 * @param motif    narrow to one failure code
 * @param city     narrow to one drop-off city
 * @param source   narrow to one order source (ERP/Odoo/App…)
 * @param depotId  narrow to deliveries routed from one depot
 */
public record AnalyticsFilter(
        UUID driverId, UUID zoneId, DeliveryStatus status, String motif,
        String city, OrderSource source, UUID depotId
) {

    public static final AnalyticsFilter NONE = new AnalyticsFilter(null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return driverId == null && zoneId == null && status == null
                && (motif == null || motif.isBlank())
                && (city == null || city.isBlank()) && source == null && depotId == null;
    }

    /** JPQL {@code AND ...} fragment for the {@code Delivery d} alias; empty when no filter is set. */
    public String jpql() {
        StringBuilder sb = new StringBuilder();
        if (driverId != null) sb.append(" AND d.driverId = :fDriverId");
        if (zoneId != null) sb.append(" AND d.order.zoneId = :fZoneId");
        if (status != null) sb.append(" AND d.status = :fStatus");
        if (motif != null && !motif.isBlank()) sb.append(" AND d.failureCode = :fMotif");
        if (city != null && !city.isBlank()) sb.append(" AND d.order.dropoffCity = :fCity");
        if (source != null) sb.append(" AND d.order.source = :fSource");
        if (depotId != null) sb.append(" AND EXISTS (SELECT 1 FROM RouteStop rs WHERE rs.deliveryId = d.id AND rs.route.depotId = :fDepot)");
        return sb.toString();
    }

    /** Native-SQL fragment, assuming {@code deliveries d} joined to {@code orders o}. */
    public String nativeSql() {
        StringBuilder sb = new StringBuilder();
        if (driverId != null) sb.append(" AND d.driver_id = :fDriverId");
        if (zoneId != null) sb.append(" AND o.zone_id = :fZoneId");
        if (status != null) sb.append(" AND d.status = :fStatusName");
        if (motif != null && !motif.isBlank()) sb.append(" AND d.failure_code = :fMotif");
        if (city != null && !city.isBlank()) sb.append(" AND o.dropoff_city = :fCity");
        if (source != null) sb.append(" AND o.source = :fSourceName");
        if (depotId != null) sb.append(" AND EXISTS (SELECT 1 FROM route_stops rs JOIN routes r ON rs.route_id = r.id WHERE rs.delivery_id = d.id AND r.depot_id = :fDepot)");
        return sb.toString();
    }

    /** Bind whichever named parameters {@link #jpql()} referenced. */
    public void bind(Query query) {
        if (driverId != null) query.setParameter("fDriverId", driverId);
        if (zoneId != null) query.setParameter("fZoneId", zoneId);
        if (status != null) query.setParameter("fStatus", status);
        if (motif != null && !motif.isBlank()) query.setParameter("fMotif", motif);
        if (city != null && !city.isBlank()) query.setParameter("fCity", city);
        if (source != null) query.setParameter("fSource", source);
        if (depotId != null) query.setParameter("fDepot", depotId);
    }

    /** Bind for {@link #nativeSql()} (enums bound by name, UUIDs as-is). */
    public void bindNative(Query query) {
        if (driverId != null) query.setParameter("fDriverId", driverId);
        if (zoneId != null) query.setParameter("fZoneId", zoneId);
        if (status != null) query.setParameter("fStatusName", status.name());
        if (motif != null && !motif.isBlank()) query.setParameter("fMotif", motif);
        if (city != null && !city.isBlank()) query.setParameter("fCity", city);
        if (source != null) query.setParameter("fSourceName", source.name());
        if (depotId != null) query.setParameter("fDepot", depotId);
    }
}
