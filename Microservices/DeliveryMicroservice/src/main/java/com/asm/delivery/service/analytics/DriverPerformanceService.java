package com.asm.delivery.service.analytics;

import com.asm.delivery.dto.analytics.AnalyticsQuery;
import com.asm.delivery.dto.response.DriverScorecardResponse;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.Zone;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.ZoneRepository;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.service.analytics.filter.AnalyticsFilter;
import com.asm.delivery.service.analytics.filter.PeriodRange;
import com.asm.delivery.service.analytics.filter.PeriodResolver;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Driver-performance analytics for the Analyse page — the "true stats" the leaderboard/scorecard UIs
 * expect: volume, success rate, on-time rate, average delay, top failure motif, and period-over-period
 * deltas. On-time / delay are computed via {@link DelayCalculationService} (the SLA source of truth),
 * so they never drift from the rest of the platform.
 *
 * <p>Isolated from {@code OpsAnalyticsService}: this is the per-driver domain. All queries honor the
 * shared {@link AnalyticsFilter} (zone/status/motif) and the Tunis-anchored {@link PeriodResolver}.
 */
@Service
@RequiredArgsConstructor
public class DriverPerformanceService {

    private final EntityManager entityManager;
    private final RouteStopRepository routeStopRepository;
    private final DelayCalculationService delayCalculationService;
    private final TransportPort transportPort;
    private final ZoneRepository zoneRepository;
    private final PeriodResolver periodResolver;
    private final ZoneResolver zoneResolver;

    /** Leaderboard (all drivers) or single-driver drilldown when {@code query.driverId} is set. */
    @Transactional(readOnly = true)
    public DriverScorecardResponse scorecards(AnalyticsQuery query) {
        PeriodRange pr = periodResolver.resolve(
                query.getRange() != null ? query.getRange() : query.getPeriod(),
                query.getLast(), query.getFrom(), query.getTo(), query.getGranularity(), query.isCompare());
        AnalyticsFilter filter = new AnalyticsFilter(
                query.getDriverId(), zoneResolver.resolveAll(query.getZone()), query.getStatus(), query.getMotif(),
                query.getCity(), query.getSource(), query.getDepot());
        Map<UUID, long[]> counts = countsByDriver(pr.start(), pr.end(), filter);      // [total, delivered, failed]
        Map<UUID, double[]> perf = perfByDriver(pr.start(), pr.end(), filter);        // [avgDelay, onTimeRate]
        Map<UUID, String> topMotif = topMotifByDriver(pr.start(), pr.end(), filter);
        Map<UUID, List<DriverScorecardResponse.DailyPoint>> trends = trendByDriver(pr.start(), pr.end(), filter);
        Map<UUID, Long> prevVolume = pr.hasComparison()
                ? volumeByDriver(pr.prevStart(), pr.prevEnd(), filter) : Map.of();
        Map<UUID, Double> prevSuccess = pr.hasComparison()
                ? successRateByDriver(pr.prevStart(), pr.prevEnd(), filter) : Map.of();

        List<DriverScorecardResponse.Scorecard> cards = new ArrayList<>();
        for (Map.Entry<UUID, long[]> e : counts.entrySet()) {
            UUID driverId = e.getKey();
            long total = e.getValue()[0];
            long delivered = e.getValue()[1];
            long failed = e.getValue()[2];
            double successRate = total > 0 ? (double) delivered / total * 100.0 : 0.0;
            double[] p = perf.getOrDefault(driverId, new double[]{0.0, 100.0});

            Double deltaVolumePct = null;
            Double deltaSuccessPts = null;
            if (pr.hasComparison()) {
                long pv = prevVolume.getOrDefault(driverId, 0L);
                deltaVolumePct = pv == 0 ? null : round2(((double) (total - pv) / pv) * 100.0);
                double ps = prevSuccess.getOrDefault(driverId, successRate);
                deltaSuccessPts = round2(successRate - ps);
            }

            cards.add(DriverScorecardResponse.Scorecard.builder()
                    .driverId(driverId.toString())
                    .driverName(null) // enriched below, outside the query loop
                    .volume(total).delivered(delivered).failed(failed)
                    .successRate(round2(successRate))
                    .avgDelayMinutes(round2(p[0]))
                    .onTimeRate(round2(p[1]))
                    .topFailureMotif(topMotif.get(driverId))
                    .deltaVolumePct(deltaVolumePct)
                    .deltaSuccessRatePts(deltaSuccessPts)
                    .trend(trends.get(driverId))
                    .build());
        }

        cards.sort((a, b) -> Long.compare(b.getVolume(), a.getVolume()));
        enrichNames(cards);

        return DriverScorecardResponse.builder()
                .period(pr.label())
                .periodStart(pr.start())
                .periodEnd(pr.end())
                .compared(pr.hasComparison())
                .drivers(cards)
                .build();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Per-driver resolved outcomes in the window — event-anchored (delivered on completedAt, failed on
     * failedAt, immutable), matching the dashboard. Returns [volume, delivered, failed] where
     * volume = delivered + failed (resolved throughput), so successRate = delivered / (delivered+failed)
     * and a reprogrammed failure no longer "heals" out of the count.
     */
    private Map<UUID, long[]> countsByDriver(LocalDateTime start, LocalDateTime end, AnalyticsFilter filter) {
        TypedQuery<Object[]> q = entityManager.createQuery(
                "SELECT d.driverId, " +
                        "SUM(CASE WHEN d.status IN (:delivered, :partial) AND d.completedAt BETWEEN :start AND :end THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN d.failedAt BETWEEN :start AND :end THEN 1 ELSE 0 END) " +
                        "FROM Delivery d WHERE d.driverId IS NOT NULL AND (" +
                        "(d.status IN (:delivered, :partial) AND d.completedAt BETWEEN :start AND :end) OR (d.failedAt BETWEEN :start AND :end)" +
                        ")" + filter.jpql() + " GROUP BY d.driverId", Object[].class)
                .setParameter("delivered", DeliveryStatus.DELIVERED)
                .setParameter("partial", DeliveryStatus.PARTIALLY_DELIVERED)
                .setParameter("start", start).setParameter("end", end);
        filter.bind(q);
        Map<UUID, long[]> out = new HashMap<>();
        for (Object[] row : q.getResultList()) {
            long delivered = num(row[1]);
            long failed = num(row[2]);
            out.put((UUID) row[0], new long[]{delivered + failed, delivered, failed});
        }
        return out;
    }

    /** Resolved throughput per driver (delivered + failed, event-anchored) — the prev-window volume. */
    private Map<UUID, Long> volumeByDriver(LocalDateTime start, LocalDateTime end, AnalyticsFilter filter) {
        TypedQuery<Object[]> q = entityManager.createQuery(
                "SELECT d.driverId, COUNT(d) FROM Delivery d WHERE d.driverId IS NOT NULL AND (" +
                        "(d.status IN (:delivered, :partial) AND d.completedAt BETWEEN :start AND :end) OR (d.failedAt BETWEEN :start AND :end)" +
                        ")" + filter.jpql() + " GROUP BY d.driverId", Object[].class)
                .setParameter("delivered", DeliveryStatus.DELIVERED)
                .setParameter("partial", DeliveryStatus.PARTIALLY_DELIVERED)
                .setParameter("start", start).setParameter("end", end);
        filter.bind(q);
        return q.getResultList().stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> num(r[1])));
    }

    /** Prev-window success rate = delivered / (delivered + failed), event-anchored (matches current). */
    private Map<UUID, Double> successRateByDriver(LocalDateTime start, LocalDateTime end, AnalyticsFilter filter) {
        TypedQuery<Object[]> q = entityManager.createQuery(
                "SELECT d.driverId, " +
                        "SUM(CASE WHEN d.status IN (:delivered, :partial) AND d.completedAt BETWEEN :start AND :end THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN d.failedAt BETWEEN :start AND :end THEN 1 ELSE 0 END) " +
                        "FROM Delivery d WHERE d.driverId IS NOT NULL AND (" +
                        "(d.status IN (:delivered, :partial) AND d.completedAt BETWEEN :start AND :end) OR (d.failedAt BETWEEN :start AND :end)" +
                        ")" + filter.jpql() + " GROUP BY d.driverId", Object[].class)
                .setParameter("delivered", DeliveryStatus.DELIVERED)
                .setParameter("partial", DeliveryStatus.PARTIALLY_DELIVERED)
                .setParameter("start", start).setParameter("end", end);
        filter.bind(q);
        Map<UUID, Double> out = new HashMap<>();
        for (Object[] row : q.getResultList()) {
            long del = num(row[1]);
            long resolved = del + num(row[2]);
            out.put((UUID) row[0], resolved > 0 ? (double) del / resolved * 100.0 : 0.0);
        }
        return out;
    }

    /** avg delay + on-time rate per driver, computed from strict stop delays (SLA source of truth). */
    private Map<UUID, double[]> perfByDriver(LocalDateTime start, LocalDateTime end, AnalyticsFilter filter) {
        TypedQuery<Object[]> q = entityManager.createQuery(
                "SELECT d.id, d.driverId FROM Delivery d " +
                        "WHERE d.driverId IS NOT NULL AND d.completedAt BETWEEN :start AND :end " +
                        "AND d.status IN (:delivered, :partial)" + filter.jpql(), Object[].class)
                .setParameter("delivered", DeliveryStatus.DELIVERED)
                .setParameter("partial", DeliveryStatus.PARTIALLY_DELIVERED)
                .setParameter("start", start).setParameter("end", end);
        filter.bind(q);
        List<Object[]> rows = q.getResultList();
        List<UUID> deliveryIds = rows.stream().map(r -> (UUID) r[0]).filter(Objects::nonNull).toList();
        Map<UUID, UUID> driverByDelivery = rows.stream()
                .filter(r -> r[0] != null && r[1] != null)
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (UUID) r[1], (a, b) -> a));

        Map<UUID, double[]> out = new HashMap<>();
        if (deliveryIds.isEmpty()) return out;

        Map<UUID, RouteStop> stopByDelivery = routeStopRepository.findAllByDeliveryIdInWithRoute(deliveryIds).stream()
                .collect(Collectors.toMap(RouteStop::getDeliveryId, s -> s, (a, b) -> {
                    if (a.getCreatedAt() == null) return b;
                    if (b.getCreatedAt() == null) return a;
                    return a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b;
                }));

        Map<UUID, List<Integer>> delaysByDriver = new HashMap<>();
        for (RouteStop stop : stopByDelivery.values()) {
            UUID deliveryId = stop.getDeliveryId();
            UUID driverId = deliveryId != null ? driverByDelivery.get(deliveryId) : null;
            if (driverId == null) continue;
            Integer delay = delayCalculationService.calculateStrictStopDelayMinutes(stop, stop.getRoute());
            if (delay == null) continue; // not measurable (no window)
            delaysByDriver.computeIfAbsent(driverId, k -> new ArrayList<>()).add(delay);
        }
        for (Map.Entry<UUID, List<Integer>> e : delaysByDriver.entrySet()) {
            List<Integer> delays = e.getValue();
            double avg = delays.stream().mapToInt(v -> Math.max(0, v)).average().orElse(0.0);
            long onTime = delays.stream().filter(v -> v <= 0).count();
            double onTimeRate = delays.isEmpty() ? 100.0 : (double) onTime / delays.size() * 100.0;
            out.put(e.getKey(), new double[]{avg, onTimeRate});
        }
        return out;
    }

    private Map<UUID, String> topMotifByDriver(LocalDateTime start, LocalDateTime end, AnalyticsFilter filter) {
        TypedQuery<Object[]> q = entityManager.createQuery(
                "SELECT d.driverId, d.failureCode, COUNT(d) FROM Delivery d " +
                        "WHERE d.driverId IS NOT NULL AND d.failureCode IS NOT NULL AND d.failedAt BETWEEN :start AND :end" + filter.jpql() + " " +
                        "GROUP BY d.driverId, d.failureCode", Object[].class)
                .setParameter("start", start).setParameter("end", end);
        filter.bind(q);
        Map<UUID, String> out = new HashMap<>();
        Map<UUID, Long> bestCount = new HashMap<>();
        for (Object[] row : q.getResultList()) {
            UUID driverId = (UUID) row[0];
            String motif = row[1] != null ? row[1].toString() : null;
            long count = num(row[2]);
            if (motif == null) continue;
            if (count > bestCount.getOrDefault(driverId, 0L)) {
                bestCount.put(driverId, count);
                out.put(driverId, motif);
            }
        }
        return out;
    }

    /**
     * Daily trend per driver for the whole leaderboard — one grouped query (driver × day), event-anchored
     * (completed ?? failed ?? created) and honoring the active pivots, so every row's sparkline is populated
     * (not just the single-driver drilldown).
     */
    private Map<UUID, List<DriverScorecardResponse.DailyPoint>> trendByDriver(LocalDateTime start, LocalDateTime end, AnalyticsFilter filter) {
        Query q = entityManager.createNativeQuery(
                "SELECT d.driver_id, CAST(COALESCE(d.completed_at, d.failed_at, d.created_at) AS date) AS dt, COUNT(*) AS total, " +
                        "SUM(CASE WHEN d.status IN ('DELIVERED','PARTIALLY_DELIVERED') THEN 1 ELSE 0 END) AS delivered " +
                        "FROM deliveries d JOIN orders o ON d.order_id = o.id " +
                        "WHERE d.driver_id IS NOT NULL AND COALESCE(d.completed_at, d.failed_at, d.created_at) BETWEEN :start AND :end" + filter.nativeSql() + " " +
                        "GROUP BY d.driver_id, dt ORDER BY dt")
                .setParameter("start", start).setParameter("end", end);
        filter.bindNative(q);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        Map<UUID, List<DriverScorecardResponse.DailyPoint>> out = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            UUID driverId = r[0] instanceof UUID ? (UUID) r[0] : UUID.fromString(r[0].toString());
            out.computeIfAbsent(driverId, k -> new ArrayList<>()).add(
                    DriverScorecardResponse.DailyPoint.builder()
                            .date(((java.sql.Date) r[1]).toLocalDate().toString())
                            .total(num(r[2]))
                            .delivered(num(r[3]))
                            .build());
        }
        return out;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void enrichNames(List<DriverScorecardResponse.Scorecard> cards) {
        // Single batch fetch (one HTTP round-trip) instead of one getDriver call per scorecard.
        Map<String, DriverDTO> byId = new HashMap<>();
        try {
            for (DriverDTO d : transportPort.getAvailableDrivers()) {
                if (d.getId() != null) byId.put(d.getId(), d);
            }
        } catch (Exception ignored) { /* names fall back to the id prefix below */ }
        for (DriverScorecardResponse.Scorecard c : cards) {
            if (c.getDriverId() == null) continue;
            DriverDTO dto = byId.get(c.getDriverId());
            c.setDriverName(dto != null ? dto.getName() : "Livreur " + c.getDriverId().substring(0, 8));
        }
    }

    private static long num(Object o) {
        return o != null ? ((Number) o).longValue() : 0L;
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
