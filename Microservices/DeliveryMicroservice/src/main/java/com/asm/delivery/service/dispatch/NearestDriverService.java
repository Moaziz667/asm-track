package com.asm.delivery.service.dispatch;

import com.asm.delivery.dto.response.NearestDriverResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.service.OsrmRoutingService;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ranks online drivers by how close they are to a delivery's drop-off, for the quick-reassign picker.
 *
 * <p>Strategy (per OSRM dispatch best practice): cheap straight-line <b>k-NN pre-filter</b> to a
 * shortlist, then ONE OSRM Table call for road-accurate ETA/distance on that shortlist. Falls back to
 * straight-line ordering when OSRM is disabled or a leg is unroutable, so the picker always returns
 * something usable. Drivers without a GPS fix, offline drivers, and the delivery's current driver are
 * excluded — they can't be ranked by live proximity / are no-ops to reassign to.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NearestDriverService {

    private static final int PREFILTER = 12; // shortlist size handed to the OSRM matrix

    /** A driver's GPS fix must be this fresh (minutes) to be ranked — live-tracking, not last-known. */
    @Value("${dispatch.nearest.gps-fresh-minutes:5}")
    private int gpsFreshMinutes;

    private final DeliveryRepository deliveryRepo;
    private final TransportPort transportPort;
    private final OsrmRoutingService osrm;

    @Transactional(readOnly = true)
    public List<NearestDriverResponse> nearest(UUID deliveryId, int limit) {
        int cap = limit > 0 ? limit : 5;
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));
        Order order = delivery.getOrder();
        Double dlat = order != null && order.getDropoffLat() != null ? order.getDropoffLat().doubleValue() : null;
        Double dlng = order != null && order.getDropoffLng() != null ? order.getDropoffLng().doubleValue() : null;

        String currentDriverId = delivery.getDriverId() != null ? delivery.getDriverId().toString() : null;
        List<DriverDTO> drivers = transportPort.getAvailableDrivers().stream()
                .filter(d -> d.getCurrentLat() != null && d.getCurrentLng() != null)
                .filter(d -> !"OFFLINE".equalsIgnoreCase(d.getOnlineStatus()))
                .filter(d -> isGpsFresh(d.getLastLocationAt())) // live position only — not a stale last-known fix
                .filter(d -> currentDriverId == null || !currentDriverId.equals(d.getId()))
                .collect(Collectors.toList());

        if (dlat == null || dlng == null || drivers.isEmpty()) {
            return List.of();
        }

        final double fdlat = dlat, fdlng = dlng;
        List<DriverDTO> shortlist = drivers.stream()
                .sorted(Comparator.comparingDouble(d -> haversineKm(fdlat, fdlng, d.getCurrentLat(), d.getCurrentLng())))
                .limit(PREFILTER)
                .collect(Collectors.toList());

        // OSRM Table: index 0 = drop-off, 1..N = drivers; durations[k][0] = driver k → drop-off.
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{fdlat, fdlng});
        shortlist.forEach(d -> points.add(new double[]{d.getCurrentLat(), d.getCurrentLng()}));

        var matrix = osrm.durationMatrix(points);
        List<NearestDriverResponse> ranked = new ArrayList<>();

        if (matrix.isPresent()) {
            double[][] dur = matrix.get().durations();
            double[][] dist = matrix.get().distances();
            for (int k = 1; k <= shortlist.size(); k++) {
                DriverDTO d = shortlist.get(k - 1);
                Double eta = dur != null && dur[k][0] > 0 ? dur[k][0] : null;
                Double dm = dist != null && dist[k][0] > 0 ? dist[k][0] : null;
                ranked.add(base(d).etaSeconds(eta != null ? (int) Math.round(eta) : null)
                        .distanceMeters(dm != null ? (int) Math.round(dm) : null)
                        .source("osrm").build());
            }
            ranked.sort(Comparator.comparingInt(r -> r.getEtaSeconds() == null ? Integer.MAX_VALUE : r.getEtaSeconds()));
        } else {
            for (DriverDTO d : shortlist) {
                double km = haversineKm(fdlat, fdlng, d.getCurrentLat(), d.getCurrentLng());
                ranked.add(base(d).etaSeconds(null).distanceMeters((int) Math.round(km * 1000))
                        .source("haversine").build());
            }
            ranked.sort(Comparator.comparingInt(r -> r.getDistanceMeters() == null ? Integer.MAX_VALUE : r.getDistanceMeters()));
        }

        return ranked.stream().limit(cap).collect(Collectors.toList());
    }

    private static NearestDriverResponse.NearestDriverResponseBuilder base(DriverDTO d) {
        return NearestDriverResponse.builder()
                .driverId(d.getId()).name(d.getName()).onlineStatus(d.getOnlineStatus())
                .currentLat(d.getCurrentLat()).currentLng(d.getCurrentLng());
    }

    /** True when the driver's last GPS fix is within the freshness window. Stale / missing → not ranked. */
    private boolean isGpsFresh(String lastLocationAt) {
        if (lastLocationAt == null || lastLocationAt.isBlank()) return false;
        try {
            LocalDateTime t;
            try { t = LocalDateTime.parse(lastLocationAt); }
            catch (Exception e) { t = OffsetDateTime.parse(lastLocationAt).toLocalDateTime(); }
            return ChronoUnit.MINUTES.between(t, LocalDateTime.now()) <= gpsFreshMinutes;
        } catch (Exception e) {
            return false;
        }
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
