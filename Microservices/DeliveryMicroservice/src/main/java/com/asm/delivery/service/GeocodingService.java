package com.asm.delivery.service;

import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GeocodingService {

        private static final List<String> LOCALITY_KEYS = List.of(
            "city",
            "town",
            "municipality",
            "village",
            "suburb",
            "hamlet"
        );

        private static final List<String> STATE_KEYS = List.of(
            "state",
            "state_district",
            "county"
        );

        private static final Pattern GOVERNORATE_IN_DISPLAY = Pattern.compile("(?:Gouvernorat|Governorate)\\s+([^,]+)", Pattern.CASE_INSENSITIVE);

    private static final String NOMINATIM_URL =
            "https://nominatim.openstreetmap.org/search?q={q}&format=json&limit=1&addressdetails=1&countrycodes=tn&accept-language=fr";

    /** Same as NOMINATIM_URL but without country restriction — used for depot/warehouse geocoding. */
    private static final String NOMINATIM_URL_GLOBAL =
            "https://nominatim.openstreetmap.org/search?q={q}&format=json&limit=1&addressdetails=1&accept-language=fr";

    private static final String NOMINATIM_REVERSE_URL =
            "https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lon}&format=json&accept-language=fr";

    private static final String NOMINATIM_SEARCH_URL =
            "https://nominatim.openstreetmap.org/search?q={q}&format=json&limit={limit}&addressdetails=1&countrycodes=tn&accept-language=fr";

    // Tunisia bounding box
    private static final double TN_LAT_MIN = 30.2;
    private static final double TN_LAT_MAX = 37.5;
    private static final double TN_LNG_MIN = 7.5;
    private static final double TN_LNG_MAX = 11.6;

    private final RestClient restClient;

    public GeocodingService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(6000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Geocodes an address query using Nominatim (Tunisia only).
     * Always returns a response — if geocoding fails or finds nothing, found=false.
     */
    public GeocodeSuggestionResponse geocode(String addressQuery) {
        if (addressQuery == null || addressQuery.isBlank()) {
            return GeocodeSuggestionResponse.builder().found(false).build();
        }

        try {
            Map<String, Object>[] results = restClient.get()
                    .uri(NOMINATIM_URL, Map.of("q", addressQuery))
                    .header("User-Agent", "ASM-Delivery-App/1.0")
                    .retrieve()
                    .body((Class<Map<String, Object>[]>) (Class<?>) Map[].class);

            if (results == null || results.length == 0) {
                log.debug("Nominatim: no results for query '{}'", addressQuery);
                return GeocodeSuggestionResponse.builder().found(false).build();
            }

            Map<String, Object> first = results[0];
            double lat = Double.parseDouble((String) first.get("lat"));
            double lng = Double.parseDouble((String) first.get("lon"));
            String displayName = (String) first.get("display_name");
            String city = extractCity(first, displayName);
            String postalCode = extractPostalCode(first);

            boolean outsideBbox = lat < TN_LAT_MIN || lat > TN_LAT_MAX
                    || lng < TN_LNG_MIN || lng > TN_LNG_MAX;

            if (outsideBbox) {
                log.warn("Nominatim result ({}, {}) is outside Tunisia bbox for query '{}'", lat, lng, addressQuery);
            }

            return GeocodeSuggestionResponse.builder()
                    .found(true)
                    .lat(lat)
                    .lng(lng)
                    .displayName(displayName)
                    .city(city)
                    .postalCode(postalCode)
                    .outsideTunisiaBbox(outsideBbox)
                    .build();

        } catch (Exception ex) {
            log.warn("Nominatim geocoding failed for query '{}': {}", addressQuery, ex.getMessage());
            return GeocodeSuggestionResponse.builder().found(false).build();
        }
    }

    /**
     * Free-text address autocomplete (Tunisia). Server-side proxy so the browser never
     * calls the public Nominatim endpoint directly (usage-policy + caching + no CORS).
     * Returns up to {@code limit} suggestions; empty list on failure.
     */
    @SuppressWarnings("unchecked")
    public List<GeocodeSuggestionResponse> searchAddresses(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        int capped = Math.min(Math.max(limit, 1), 8);
        try {
            Map<String, Object>[] results = restClient.get()
                    .uri(NOMINATIM_SEARCH_URL, Map.of("q", query, "limit", capped))
                    .header("User-Agent", "ASM-Delivery-App/1.0")
                    .retrieve()
                    .body((Class<Map<String, Object>[]>) (Class<?>) Map[].class);

            if (results == null || results.length == 0) return List.of();

            List<GeocodeSuggestionResponse> out = new ArrayList<>();
            for (Map<String, Object> r : results) {
                try {
                    double lat = Double.parseDouble((String) r.get("lat"));
                    double lng = Double.parseDouble((String) r.get("lon"));
                    String displayName = (String) r.get("display_name");
                    out.add(GeocodeSuggestionResponse.builder()
                            .found(true)
                            .lat(lat)
                            .lng(lng)
                            .displayName(displayName)
                            .city(extractCity(r, displayName))
                            .postalCode(extractPostalCode(r))
                            .build());
                } catch (Exception ignore) {
                    // skip malformed row
                }
            }
            return out;
        } catch (Exception ex) {
            log.warn("Nominatim search failed for '{}': {}", query, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Geocodes an address without country restriction (global).
     * Used for depot/warehouse sync where addresses may not be in Tunisia.
     * Always returns a response — if geocoding fails or finds nothing, found=false.
     */
    public GeocodeSuggestionResponse geocodeGlobal(String addressQuery) {
        if (addressQuery == null || addressQuery.isBlank()) {
            return GeocodeSuggestionResponse.builder().found(false).build();
        }

        try {
            Map<String, Object>[] results = restClient.get()
                    .uri(NOMINATIM_URL_GLOBAL, Map.of("q", addressQuery))
                    .header("User-Agent", "ASM-Delivery-App/1.0")
                    .retrieve()
                    .body((Class<Map<String, Object>[]>) (Class<?>) Map[].class);

            if (results == null || results.length == 0) {
                log.debug("Nominatim global: no results for query '{}'", addressQuery);
                return GeocodeSuggestionResponse.builder().found(false).build();
            }

            Map<String, Object> first = results[0];
            double lat = Double.parseDouble((String) first.get("lat"));
            double lng = Double.parseDouble((String) first.get("lon"));
            String displayName = (String) first.get("display_name");
            String city = extractCity(first, displayName);
            String postalCode = extractPostalCode(first);

            boolean outsideBbox = lat < TN_LAT_MIN || lat > TN_LAT_MAX
                    || lng < TN_LNG_MIN || lng > TN_LNG_MAX;

            return GeocodeSuggestionResponse.builder()
                    .found(true)
                    .lat(lat)
                    .lng(lng)
                    .displayName(displayName)
                    .city(city)
                    .postalCode(postalCode)
                    .outsideTunisiaBbox(outsideBbox)
                    .build();

        } catch (Exception ex) {
            log.warn("Nominatim global geocoding failed for query '{}': {}", addressQuery, ex.getMessage());
            return GeocodeSuggestionResponse.builder().found(false).build();
        }
    }

    /**
     * Reverse geocodes a lat/lng pin using Nominatim.
     * Returns displayName, city (extracted from address), postalCode.
     * Always returns a result — empty strings on failure.
     */
    @SuppressWarnings("unchecked")
    public GeocodeSuggestionResponse reverseGeocode(double lat, double lng) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(NOMINATIM_REVERSE_URL, Map.of("lat", lat, "lon", lng))
                    .header("User-Agent", "ASM-Delivery-App/1.0")
                    .retrieve()
                    .body((Class<Map<String, Object>>) (Class<?>) Map.class);

            if (body == null || body.get("error") != null) {
                log.debug("Nominatim reverse: no result for ({}, {})", lat, lng);
                return GeocodeSuggestionResponse.builder().found(false).lat(lat).lng(lng).build();
            }

            String displayName = (String) body.getOrDefault("display_name", "");

            // Extract city and postal code from address sub-object
            String city = extractCity(body, displayName);
            String postalCode = extractPostalCode(body);

            boolean outsideBbox = lat < TN_LAT_MIN || lat > TN_LAT_MAX
                    || lng < TN_LNG_MIN || lng > TN_LNG_MAX;

            return GeocodeSuggestionResponse.builder()
                    .found(true)
                    .lat(lat)
                    .lng(lng)
                    .displayName(displayName)
                    .city(city)
                    .postalCode(postalCode)
                    .outsideTunisiaBbox(outsideBbox)
                    .build();

        } catch (Exception ex) {
            log.warn("Nominatim reverse geocoding failed for ({}, {}): {}", lat, lng, ex.getMessage());
            return GeocodeSuggestionResponse.builder().found(false).lat(lat).lng(lng).build();
        }
    }

    /**
     * Extracts the operational city from a Nominatim result's {@code address} sub-object
     * (present when {@code addressdetails=1}). Shared by forward + reverse geocoding.
     */
    private String extractCity(Map<String, Object> result, String displayName) {
        Object addressObj = result.get("address");
        if (addressObj instanceof Map<?, ?> addr) {
            return extractPreferredTunisianCity(addr, displayName);
        }
        return null;
    }

    /** Extracts the postal code from a Nominatim result's {@code address} sub-object. */
    private String extractPostalCode(Map<String, Object> result) {
        Object addressObj = result.get("address");
        if (addressObj instanceof Map<?, ?> addr) {
            Object pc = addr.get("postcode");
            if (pc instanceof String s && !s.isBlank()) return s.trim();
        }
        return null;
    }

    private String cleanTunisianAdminName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.startsWith("gouvernorat ")) {
            return trimmed.substring("gouvernorat ".length()).trim();
        }
        if (lower.startsWith("governorate ")) {
            return trimmed.substring("governorate ".length()).trim();
        }
        if (lower.startsWith("délégation ")) {
            return trimmed.substring("délégation ".length()).trim();
        }
        if (lower.startsWith("delegation ")) {
            return trimmed.substring("delegation ".length()).trim();
        }
        return trimmed;
    }

    private String extractPreferredTunisianCity(Map<?, ?> addr, String displayName) {
        String state = null;
        for (String key : STATE_KEYS) {
            Object v = addr.get(key);
            if (v instanceof String s && !s.isBlank()) {
                state = cleanTunisianAdminName(s);
                break;
            }
        }

        if (!org.springframework.util.StringUtils.hasText(state)) {
            state = extractGovernorateFromDisplay(displayName);
        }

        String locality = null;
        for (String key : LOCALITY_KEYS) {
            Object v = addr.get(key);
            if (v instanceof String s && !s.isBlank()) {
                locality = cleanTunisianAdminName(s);
                break;
            }
        }

        if (!org.springframework.util.StringUtils.hasText(locality)) {
            return state;
        }
        if (!org.springframework.util.StringUtils.hasText(state)) {
            return locality;
        }

        // If reverse geocoding returns a micro-locality (Merkez/Delegation/etc.),
        // prefer governorate-level city naming for operational consistency.
        if (isMicroLocality(locality)) {
            return state;
        }

        String localityLower = locality.toLowerCase(Locale.ROOT);
        String stateLower = state.toLowerCase(Locale.ROOT);
        if (localityLower.contains(stateLower)) {
            return state;
        }

        return locality;
    }

    private String extractGovernorateFromDisplay(String displayName) {
        if (!org.springframework.util.StringUtils.hasText(displayName)) {
            return null;
        }
        Matcher matcher = GOVERNORATE_IN_DISPLAY.matcher(displayName);
        if (matcher.find()) {
            return cleanTunisianAdminName(matcher.group(1));
        }
        return null;
    }

    private boolean isMicroLocality(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.contains("delegation")
                || lowered.contains("délégation")
                || lowered.contains("merkez")
                || lowered.contains("centre")
                || lowered.contains("arrondissement");
    }
}
