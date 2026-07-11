package com.asm.delivery.service.analytics.filter;

import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.OrderSource;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reusable, server-side scope filter for analytics queries — every "who/where/what" pivot that can
 * narrow a delivery set (the time dimension is handled separately by {@link PeriodResolver}).
 *
 * <p>Every pivot is multi-value (OR within a field, AND across fields): {@code IN} clauses spliced
 * into the dynamic JPQL/native builders. The JPQL fragment assumes the {@code Delivery} alias is
 * {@code d}; zone/city/source use the implicit-join path {@code d.order.*}; depot uses an
 * {@code EXISTS} sub-query over the route stops.
 *
 * <p>All fields optional — a null/empty list contributes no clause and binds no parameter, so
 * {@link #NONE} is a true no-op.
 *
 * @param driverIds narrow to these drivers
 * @param zoneIds   narrow to these zones (resolved from names upstream)
 * @param statuses  narrow to these delivery statuses
 * @param motifs    narrow to these failure codes (coarse {@link FailureCode}; unresolved values dropped)
 * @param cities    narrow to these drop-off cities
 * @param sources   narrow to these order sources (ERP/Odoo/App…)
 * @param depotIds  narrow to deliveries routed from these depots
 */
public record AnalyticsFilter(
        List<UUID> driverIds, List<UUID> zoneIds, List<DeliveryStatus> statuses, List<String> motifs,
        List<String> cities, List<OrderSource> sources, List<UUID> depotIds
) {

    public static final AnalyticsFilter NONE = new AnalyticsFilter(null, null, null, null, null, null, null);

    private static boolean has(List<?> l) {
        return l != null && !l.isEmpty();
    }

    /**
     * {@code Delivery.failureCode} is the coarse {@link FailureCode} enum (CLIENT_ABSENT, REFUSED…),
     * not the granular reason-catalog code. Parse each motif to that enum; anything that doesn't map
     * (blank, unknown, or a granular catalog code) is dropped — so an all-unresolvable motif list is a
     * no-op rather than a 500.
     */
    private List<FailureCode> motifCodes() {
        if (!has(motifs)) return List.of();
        List<FailureCode> out = new ArrayList<>();
        for (String m : motifs) {
            if (m == null || m.isBlank()) continue;
            try {
                out.add(FailureCode.valueOf(m.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // granular / unknown code — not queryable on d.failureCode, skip it
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return !has(driverIds) && !has(zoneIds) && !has(statuses)
                && motifCodes().isEmpty()
                && !has(cities) && !has(sources) && !has(depotIds);
    }

    /** JPQL {@code AND ...} fragment for the {@code Delivery d} alias; empty when no filter is set. */
    public String jpql() {
        StringBuilder sb = new StringBuilder();
        if (has(driverIds)) sb.append(" AND d.driverId IN :fDriverIds");
        if (has(zoneIds)) sb.append(" AND d.order.zoneId IN :fZoneIds");
        if (has(statuses)) sb.append(" AND d.status IN :fStatuses");
        if (!motifCodes().isEmpty()) sb.append(" AND d.failureCode IN :fMotifs");
        if (has(cities)) sb.append(" AND d.order.dropoffCity IN :fCities");
        if (has(sources)) sb.append(" AND d.order.source IN :fSources");
        if (has(depotIds)) sb.append(" AND EXISTS (SELECT 1 FROM RouteStop rs WHERE rs.deliveryId = d.id AND rs.route.depotId IN :fDepots)");
        return sb.toString();
    }

    /** Native-SQL fragment, assuming {@code deliveries d} joined to {@code orders o}. */
    public String nativeSql() {
        StringBuilder sb = new StringBuilder();
        if (has(driverIds)) sb.append(" AND d.driver_id IN (:fDriverIds)");
        if (has(zoneIds)) sb.append(" AND o.zone_id IN (:fZoneIds)");
        if (has(statuses)) sb.append(" AND d.status IN (:fStatusNames)");
        if (!motifCodes().isEmpty()) sb.append(" AND d.failure_code IN (:fMotifs)");
        if (has(cities)) sb.append(" AND o.dropoff_city IN (:fCities)");
        if (has(sources)) sb.append(" AND o.source IN (:fSourceNames)");
        if (has(depotIds)) sb.append(" AND EXISTS (SELECT 1 FROM route_stops rs JOIN routes r ON rs.route_id = r.id WHERE rs.delivery_id = d.id AND r.depot_id IN (:fDepots))");
        return sb.toString();
    }

    /** Bind whichever named parameters {@link #jpql()} referenced. */
    public void bind(Query query) {
        if (has(driverIds)) query.setParameter("fDriverIds", driverIds);
        if (has(zoneIds)) query.setParameter("fZoneIds", zoneIds);
        if (has(statuses)) query.setParameter("fStatuses", statuses);
        List<FailureCode> mc = motifCodes();
        if (!mc.isEmpty()) query.setParameter("fMotifs", mc);
        if (has(cities)) query.setParameter("fCities", cities);
        if (has(sources)) query.setParameter("fSources", sources);
        if (has(depotIds)) query.setParameter("fDepots", depotIds);
    }

    /** Bind for {@link #nativeSql()} (enums bound by name, UUIDs as-is). */
    public void bindNative(Query query) {
        if (has(driverIds)) query.setParameter("fDriverIds", driverIds);
        if (has(zoneIds)) query.setParameter("fZoneIds", zoneIds);
        if (has(statuses)) query.setParameter("fStatusNames", statuses.stream().map(Enum::name).toList());
        List<FailureCode> mc = motifCodes();
        if (!mc.isEmpty()) query.setParameter("fMotifs", mc.stream().map(Enum::name).toList());
        if (has(cities)) query.setParameter("fCities", cities);
        if (has(sources)) query.setParameter("fSourceNames", sources.stream().map(Enum::name).toList());
        if (has(depotIds)) query.setParameter("fDepots", depotIds);
    }
}
