package com.asm.delivery.service;

import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Best-effort enrichment of an imported order: forward-geocode the dropoff address and
 * auto-assign its delivery zone. Runs asynchronously on the single-thread
 * {@code geocodingExecutor} so it never blocks the import response and serializes Nominatim
 * calls. Geocoding failure is non-fatal: the order simply stays unpinned and the dispatcher
 * pins it manually (the existing fallback). A later manual pin overrides the auto coordinates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderGeocodingService {

    private final OrderRepository orderRepository;
    private final ZoneRepository zoneRepository;
    private final GeocodingService geocodingService;

    /**
     * Re-runs auto-geocoding for every order that is still unlocated (null coords).
     * Each runs on the throttled geocoding executor. Returns how many were queued.
     */
    public int reEnrichMissing() {
        List<UUID> ids = orderRepository.findIdsMissingCoordinates();
        ids.forEach(this::enrichOrderAsync);
        log.info("Queued {} unlocated orders for re-geocoding", ids.size());
        return ids.size();
    }

    @Async("geocodingExecutor")
    @Transactional
    public void enrichOrderAsync(UUID orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) return;

            // Throttle to ~1 req/s (Nominatim usage policy) when several BLs import together.
            try {
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // 1) Forward-geocode the address — only if not already located/pinned.
            if (order.getDropoffLat() == null || order.getDropoffLng() == null) {
                GeocodeSuggestionResponse geo = forwardGeocodeWithFallback(order);
                if (geo != null && geo.isFound() && geo.getLat() != null && geo.getLng() != null) {
                    order.setDropoffLat(BigDecimal.valueOf(geo.getLat()));
                    order.setDropoffLng(BigDecimal.valueOf(geo.getLng()));
                    backfillPostalAndCity(order, geo);
                    log.info("Auto-geocoded order {} -> ({}, {}) postal='{}'",
                            orderId, geo.getLat(), geo.getLng(), order.getDropoffPostalCode());
                } else {
                    log.info("Auto-geocode found nothing for order {} — left unpinned for manual fix", orderId);
                }
            }

            // 2) Auto-assign zone by postal code, falling back to city.
            assignZone(order);

            orderRepository.save(order);
        } catch (Exception e) {
            log.warn("Auto-geocode/zone enrichment failed for order {}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Forward-geocode the full dropoff address; if that resolves nothing (messy/partial
     * ERP addresses are common), fall back to the city/governorate so the order still
     * gets usable coordinates + a zone. Mirrors how depots resolve to a coarse point
     * rather than staying unlocated.
     */
    private GeocodeSuggestionResponse forwardGeocodeWithFallback(Order order) {
        GeocodeSuggestionResponse geo = geocodingService.geocode(buildQuery(order));
        if (geo != null && geo.isFound() && geo.getLat() != null && geo.getLng() != null) {
            return geo;
        }
        // Fallback: city (or governorate) only — coarse but enough for routing + zone detection.
        String city = order.getDropoffCity();
        if (StringUtils.hasText(city)) {
            GeocodeSuggestionResponse cityGeo = geocodingService.geocode(city.trim() + ", Tunisie");
            if (cityGeo != null && cityGeo.isFound()) {
                log.info("Auto-geocode fell back to city level for order {} (city='{}')", order.getId(), city);
                return cityGeo;
            }
        }
        return geo;
    }

    /**
     * Backfills the dropoff postal code (and city) from the geocoder when the dispatcher
     * left them blank — the manual zip field is optional, so most imported/created orders
     * arrive without one, which previously broke zone auto-detection (keyed on postal code).
     * Never overwrites a value the dispatcher actually entered.
     */
    private void backfillPostalAndCity(Order order, GeocodeSuggestionResponse geo) {
        if (!StringUtils.hasText(order.getDropoffPostalCode()) && StringUtils.hasText(geo.getPostalCode())) {
            order.setDropoffPostalCode(geo.getPostalCode().trim());
        }
        if (!StringUtils.hasText(order.getDropoffCity()) && StringUtils.hasText(geo.getCity())) {
            order.setDropoffCity(geo.getCity().trim());
        }
    }

    private String buildQuery(Order order) {
        StringBuilder sb = new StringBuilder();
        String address = order.getDropoffAddress();
        if (StringUtils.hasText(address) && !"Address not provided".equalsIgnoreCase(address)) {
            sb.append(address.trim());
        }
        if (StringUtils.hasText(order.getDropoffCity())) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(order.getDropoffCity().trim());
        }
        if (sb.length() > 0) sb.append(", ");
        sb.append("Tunisie");
        return sb.toString();
    }

    private void assignZone(Order order) {
        String postal = order.getDropoffPostalCode();
        if (StringUtils.hasText(postal)) {
            var byPostal = zoneRepository.findActiveByPostalCodeMember(postal.trim());
            if (byPostal.isPresent()) {
                order.setZoneId(byPostal.get().getId());
                return;
            }
        }
        String city = order.getDropoffCity();
        if (StringUtils.hasText(city)) {
            zoneRepository.findActiveByCityMember(city.trim()).ifPresent(z -> order.setZoneId(z.getId()));
        }
    }
}
