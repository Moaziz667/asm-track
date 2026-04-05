package com.asm.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OsrmRoutingService {

    private final ObjectMapper objectMapper;

    @Value("${routing.osrm.enabled:false}")
    private boolean enabled;

    @Value("${routing.osrm.base-url:http://osrm:5000}")
    private String baseUrl;

    @Value("${routing.osrm.profile:driving}")
    private String profile;

    @Value("${routing.osrm.connect-timeout-ms:1500}")
    private int connectTimeoutMs;

    @Value("${routing.osrm.read-timeout-ms:3000}")
    private int readTimeoutMs;

    @Value("${routing.transit-sla.multiplier:1.20}")
    private double slaMultiplier;

    @Value("${routing.transit-sla.buffer-minutes:8}")
    private int slaBufferMinutes;

    @Value("${routing.transit-sla.min-minutes:15}")
    private int slaMinMinutes;

    @Value("${routing.transit-sla.max-minutes:240}")
    private int slaMaxMinutes;

    // ─── Existing point-to-point route (kept for backwards compatibility) ────────

    public Optional<RouteSnapshot> computeRoute(BigDecimal originLat,
                                                BigDecimal originLng,
                                                BigDecimal destinationLat,
                                                BigDecimal destinationLng,
                                                LocalDateTime baseline) {
        if (!enabled) {
            return Optional.empty();
        }
        if (originLat == null || originLng == null || destinationLat == null || destinationLng == null) {
            return Optional.empty();
        }

        try {
            String requestUrl = String.format(
                    Locale.ROOT,
                    "%s/route/v1/%s/%s,%s;%s,%s?overview=full&geometries=geojson&steps=false",
                    stripTrailingSlash(baseUrl),
                    profile,
                    originLng.stripTrailingZeros().toPlainString(),
                    originLat.stripTrailingZeros().toPlainString(),
                    destinationLng.stripTrailingZeros().toPlainString(),
                    destinationLat.stripTrailingZeros().toPlainString()
            );

            RestTemplate client = buildClient();
            String raw = client.getForObject(URI.create(requestUrl), String.class);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(raw);
            JsonNode firstRoute = root.path("routes").isArray() && root.path("routes").size() > 0
                    ? root.path("routes").get(0)
                    : null;
            if (firstRoute == null || firstRoute.isMissingNode()) {
                return Optional.empty();
            }

            double distanceMeters = firstRoute.path("distance").asDouble(0d);
            double durationSeconds = firstRoute.path("duration").asDouble(0d);
            int durationMinutes = (int) Math.ceil(durationSeconds / 60d);
            if (durationMinutes <= 0) {
                return Optional.empty();
            }

            int computedSla = clamp(
                    (int) Math.ceil(durationMinutes * slaMultiplier) + slaBufferMinutes,
                    slaMinMinutes,
                    slaMaxMinutes
            );

            JsonNode coordinates = firstRoute.path("geometry").path("coordinates");
            List<List<Double>> pathCoordinates = new ArrayList<>();
            if (coordinates.isArray()) {
                for (JsonNode point : coordinates) {
                    if (point.isArray() && point.size() >= 2) {
                        double lng = point.get(0).asDouble();
                        double lat = point.get(1).asDouble();
                        pathCoordinates.add(List.of(lat, lng));
                    }
                }
            }

            String serializedPath = pathCoordinates.isEmpty() ? null : objectMapper.writeValueAsString(pathCoordinates);
            BigDecimal distanceKm = BigDecimal.valueOf(distanceMeters / 1000d).setScale(3, RoundingMode.HALF_UP);
            LocalDateTime etaAt = (baseline != null ? baseline : LocalDateTime.now()).plusMinutes(durationMinutes);

            return Optional.of(new RouteSnapshot(
                    serializedPath,
                    distanceKm,
                    durationMinutes,
                    etaAt,
                    computedSla,
                    "osrm"
            ));
        } catch (Exception ex) {
            log.warn("OSRM route computation failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // ─── 2.1 Route between two points (returns RouteSegment with geometry) ───────

    public Optional<RouteSegment> routeSegment(double lat1, double lng1, double lat2, double lng2) {
        if (!enabled) return Optional.empty();
        try {
            String url = String.format(
                    Locale.ROOT,
                    "%s/route/v1/%s/%f,%f;%f,%f?overview=full&geometries=geojson&steps=false",
                    stripTrailingSlash(baseUrl), profile,
                    lng1, lat1, lng2, lat2
            );
            String raw = buildClient().getForObject(URI.create(url), String.class);
            if (raw == null) return Optional.empty();

            JsonNode root = objectMapper.readTree(raw);
            if (!"Ok".equals(root.path("code").asText())) return Optional.empty();

            JsonNode route = root.path("routes").get(0);
            double duration = route.path("duration").asDouble();
            double distance = route.path("distance").asDouble();
            String geometry = objectMapper.writeValueAsString(route.path("geometry"));

            return Optional.of(new RouteSegment(duration, distance, geometry));
        } catch (Exception ex) {
            log.warn("OSRM routeSegment failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // ─── 2.2 Duration/Distance matrix ────────────────────────────────────────────

    /**
     * @param points list of [lat, lng] pairs; depot must be index 0
     */
    public Optional<DurationMatrix> durationMatrix(List<double[]> points) {
        if (!enabled || points == null || points.size() < 2) return Optional.empty();

        String coords = points.stream()
                .map(p -> String.format(Locale.ROOT, "%f,%f", p[1], p[0])) // lng,lat
                .collect(Collectors.joining(";"));

        // Try with both duration and distance annotations first
        Optional<DurationMatrix> result = tryDurationMatrix(coords, points.size(), "duration,distance");
        if (result.isPresent()) return result;

        // Fallback: duration only (supported by all OSRM versions)
        log.info("OSRM Table API: distance annotation unavailable, falling back to duration-only");
        return tryDurationMatrix(coords, points.size(), "duration");
    }

    private Optional<DurationMatrix> tryDurationMatrix(String coords, int n, String annotations) {
        try {
            String url = String.format(
                    Locale.ROOT,
                    "%s/table/v1/%s/%s?annotations=%s",
                    stripTrailingSlash(baseUrl), profile, coords, annotations
            );
            String raw = buildClient().getForObject(URI.create(url), String.class);
            if (raw == null) return Optional.empty();

            JsonNode root = objectMapper.readTree(raw);
            if (!"Ok".equals(root.path("code").asText())) return Optional.empty();

            double[][] durations = parseMatrix(root.path("durations"), n);
            double[][] distances = root.path("distances").isArray()
                    ? parseMatrix(root.path("distances"), n)
                    : new double[n][n]; // zeros when distance annotation is not available

            return Optional.of(new DurationMatrix(durations, distances));
        } catch (Exception ex) {
            log.warn("OSRM durationMatrix (annotations={}) failed: {}", annotations, ex.getMessage());
            return Optional.empty();
        }
    }

    // ─── 2.2b Full route geometry (depot → all stops in order) ──────────────────

    /**
     * Returns the full OSRM route geometry as a JSON-serialized [[lat,lng],...] array.
     * Makes a single OSRM Route API call with all waypoints.
     *
     * @param points list of [lat, lng] pairs; depot at index 0, then stops in order
     */
    public Optional<String> routeFullGeometry(List<double[]> points) {
        if (!enabled || points == null || points.size() < 2) return Optional.empty();
        try {
            String coords = points.stream()
                    .map(p -> String.format(Locale.ROOT, "%f,%f", p[1], p[0])) // lng,lat
                    .collect(Collectors.joining(";"));

            String url = String.format(
                    Locale.ROOT,
                    "%s/route/v1/%s/%s?overview=full&geometries=geojson&steps=false",
                    stripTrailingSlash(baseUrl), profile, coords
            );
            String raw = buildClient().getForObject(URI.create(url), String.class);
            if (raw == null) return Optional.empty();

            JsonNode root = objectMapper.readTree(raw);
            if (!"Ok".equals(root.path("code").asText())) return Optional.empty();

            JsonNode coordinates = root.path("routes").get(0).path("geometry").path("coordinates");
            List<double[]> path = new ArrayList<>();
            if (coordinates.isArray()) {
                for (JsonNode point : coordinates) {
                    // OSRM returns [lng, lat]; convert to [lat, lng] for Leaflet
                    path.add(new double[]{point.get(1).asDouble(), point.get(0).asDouble()});
                }
            }

            return path.isEmpty() ? Optional.empty() : Optional.of(objectMapper.writeValueAsString(path));
        } catch (Exception ex) {
            log.warn("OSRM routeFullGeometry failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // ─── 2.3 Trip optimization (OSRM TSP solver) ─────────────────────────────────

    /**
     * @param points list of [lat, lng] pairs; depot must be index 0
     */
    public Optional<OptimizedRoute> optimizeTrip(List<double[]> points) {
        if (!enabled || points == null || points.size() < 2) return Optional.empty();
        try {
            String coords = points.stream()
                    .map(p -> String.format(Locale.ROOT, "%f,%f", p[1], p[0])) // lng,lat
                    .collect(Collectors.joining(";"));

            String url = String.format(
                    Locale.ROOT,
                    "%s/trip/v1/%s/%s?source=first&roundtrip=false&geometries=geojson&overview=full",
                    stripTrailingSlash(baseUrl), profile, coords
            );
            String raw = buildClient().getForObject(URI.create(url), String.class);
            if (raw == null) return Optional.empty();

            JsonNode root = objectMapper.readTree(raw);
            if (!"Ok".equals(root.path("code").asText())) return Optional.empty();

            JsonNode waypoints = root.path("waypoints");
            // waypoints[i].waypoint_index gives the optimized position of input point i
            // We need the reverse: position j → which input index is there
            int n = waypoints.size();
            int[] inputIndexAtPosition = new int[n];
            for (int i = 0; i < n; i++) {
                int wpIdx = waypoints.get(i).path("waypoint_index").asInt();
                inputIndexAtPosition[wpIdx] = i;
            }

            // Build optimized order: skip index 0 (depot) since it's always first
            List<Integer> optimizedOrder = new ArrayList<>();
            for (int pos = 1; pos < n; pos++) {
                optimizedOrder.add(inputIndexAtPosition[pos] - 1); // shift by 1 since depot is 0
            }

            JsonNode trip = root.path("trips").get(0);
            double totalDuration = trip.path("duration").asDouble();
            double totalDistance = trip.path("distance").asDouble();

            List<RouteSegment> legs = new ArrayList<>();
            JsonNode legsNode = trip.path("legs");
            if (legsNode.isArray()) {
                for (JsonNode leg : legsNode) {
                    legs.add(new RouteSegment(
                            leg.path("duration").asDouble(),
                            leg.path("distance").asDouble(),
                            null // geometry per leg not requested separately here
                    ));
                }
            }

            return Optional.of(new OptimizedRoute(optimizedOrder, totalDuration, totalDistance, legs));
        } catch (Exception ex) {
            log.warn("OSRM optimizeTrip failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // ─── Nearest-neighbor fallback TSP heuristic using duration matrix ────────────

    /**
     * Falls back to nearest-neighbor when OSRM Trip API is unavailable.
     * Returns indices (0-based, excluding depot) in visiting order.
     */
    public List<Integer> nearestNeighborOrder(double[][] durations, int numStops) {
        // durations[0..numStops][0..numStops], index 0 = depot, 1..numStops = stops
        boolean[] visited = new boolean[numStops + 1];
        visited[0] = true; // depot already visited

        List<Integer> order = new ArrayList<>();
        int current = 0;
        for (int i = 0; i < numStops; i++) {
            double best = Double.MAX_VALUE;
            int next = -1;
            for (int j = 1; j <= numStops; j++) {
                if (!visited[j] && durations[current][j] < best) {
                    best = durations[current][j];
                    next = j;
                }
            }
            if (next == -1) break;
            visited[next] = true;
            order.add(next - 1); // convert to 0-based stop index
            current = next;
        }
        return order;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private double[][] parseMatrix(JsonNode node, int n) {
        double[][] matrix = new double[n][n];
        if (!node.isArray()) return matrix;
        for (int i = 0; i < n && i < node.size(); i++) {
            JsonNode row = node.get(i);
            if (!row.isArray()) continue;
            for (int j = 0; j < n && j < row.size(); j++) {
                matrix[i][j] = row.get(j).asDouble(0d);
            }
        }
        return matrix;
    }

    private RestTemplate buildClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(requestFactory);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ─── Records ──────────────────────────────────────────────────────────────────

    public record RouteSnapshot(
            String geometry,
            BigDecimal distanceKm,
            int durationMinutes,
            LocalDateTime etaAt,
            int computedTransitSlaMinutes,
            String provider
    ) {}

    public record RouteSegment(
            double durationSeconds,
            double distanceMeters,
            String geometryGeoJson
    ) {}

    public record DurationMatrix(
            double[][] durations,
            double[][] distances
    ) {}

    public record OptimizedRoute(
            List<Integer> optimizedOrder,
            double totalDurationSeconds,
            double totalDistanceMeters,
            List<RouteSegment> legs
    ) {}
}
