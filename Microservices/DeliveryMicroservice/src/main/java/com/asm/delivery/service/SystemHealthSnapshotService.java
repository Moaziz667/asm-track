package com.asm.delivery.service;

import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.OrderRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the operator System Health snapshot on a background schedule and caches it, so the
 * {@code /api/admin/system/health} endpoint never blocks the request thread on network probes
 * (the old design opened fresh TCP sockets per request, slowest exactly during an outage).
 *
 * <p>Probes use the services' own {@code /actuator/health} where available (driver, erp-adapter) for
 * a real signal; Keycloak/OSRM fall back to a shallow TCP reachability check. The ERP sync section is
 * sourced from {@code orders.odoo_sync_status} — the accurate signal — not DLQ depth.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemHealthSnapshotService {

    private final DlqReplayService dlqReplayService;
    private final ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistry;
    private final OrderRepository orderRepo;
    private final DataSource dataSource;

    @Value("${driver.service.url:http://driver-service:8086}")
    private String driverServiceUrl;
    @Value("${erp.adapter-url:http://erp-adapter:8088}")
    private String erpAdapterUrl;
    @Value("${auth.server.url:http://keycloak:8080/realms/asm}")
    private String authServerUrl;
    @Value("${app.osrm.base-url:http://localhost:5000}")
    private String osrmUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();

    private volatile Map<String, Object> snapshot;

    /** Rolling history of compact health points (~1h at the 10s cadence) for the console's trend
     *  sparklines and per-component status timelines. In-memory only — resets on restart, which is
     *  fine for an operational at-a-glance view (it is not an audit/metrics store). */
    private static final int HISTORY_CAPACITY = 360; // ~1 hour at fixedDelay=10s
    private final java.util.Deque<Map<String, Object>> history = new java.util.ArrayDeque<>(HISTORY_CAPACITY + 8);

    /** Cached snapshot; builds one synchronously on first call before the scheduler has run. */
    public Map<String, Object> current() {
        Map<String, Object> s = snapshot;
        if (s == null) {
            s = build();
            snapshot = s;
            recordHistory(s);
        }
        return s;
    }

    /** Snapshot of the rolling history, oldest → newest (a copy, safe to serialize off-thread). */
    public List<Map<String, Object>> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void refresh() {
        try {
            Map<String, Object> s = build();
            snapshot = s;
            recordHistory(s);
        } catch (Exception e) {
            log.warn("System health snapshot refresh failed: {}", e.getMessage());
        }
    }

    private void recordHistory(Map<String, Object> snap) {
        Map<String, Object> point = buildHistoryPoint(snap);
        synchronized (history) {
            history.addLast(point);
            while (history.size() > HISTORY_CAPACITY) history.removeFirst();
        }
    }

    /**
     * Distils a full snapshot into a compact, chart-ready point: the headline signal scalars plus a
     * per-component tone (ok/warn/down) so the UI can draw status swimlanes without re-deriving grouping.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildHistoryPoint(Map<String, Object> snap) {
        List<Map<String, Object>> breakers = (List<Map<String, Object>>) snap.getOrDefault("circuitBreakers", List.of());
        Map<String, Object> dlq = (Map<String, Object>) snap.getOrDefault("dlq", Map.of());
        Map<String, Object> db = (Map<String, Object>) snap.getOrDefault("db", Map.of());
        Map<String, Object> erpSync = (Map<String, Object>) snap.getOrDefault("erpSync", Map.of());

        long erpFailed = toLong(erpSync.get("failed"));
        long dlqTotal = dlq.values().stream().mapToLong(SystemHealthSnapshotService::toLong).sum();
        boolean dbReachable = !Boolean.FALSE.equals(db.get("reachable"));

        int breakersOpen = 0;
        double maxFailureRate = 0;
        String driversTone = "ok";
        String erpTone = "ok";
        for (Map<String, Object> b : breakers) {
            String state = String.valueOf(b.get("state"));
            boolean reachable = !Boolean.FALSE.equals(b.get("reachable"));
            boolean open = "OPEN".equals(state) || "FORCED_OPEN".equals(state) || !reachable;
            boolean half = "HALF_OPEN".equals(state);
            if (open) breakersOpen++;
            double fr = toDouble(b.get("failureRate"));
            if (toLong(b.get("bufferedCalls")) > 0 && fr >= 0 && fr > maxFailureRate) maxFailureRate = fr;
            String name = String.valueOf(b.get("name")).toLowerCase();
            String tone = open ? "down" : half ? "warn" : "ok";
            if (name.contains("driver")) driversTone = worse(driversTone, tone);
            if (name.contains("erp") || name.contains("adapter")) erpTone = worse(erpTone, tone);
        }
        if (erpFailed > 0) erpTone = worse(erpTone, "down");

        Map<String, String> components = new LinkedHashMap<>();
        components.put("drivers", driversTone);
        components.put("erp", erpTone);
        components.put("db", dbReachable ? "ok" : "down");
        components.put("queues", dlqTotal > 0 ? "down" : "ok");

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("t", System.currentTimeMillis());
        point.put("erpFailed", erpFailed);
        point.put("maxFailureRate", maxFailureRate);
        point.put("dlqTotal", dlqTotal);
        point.put("breakersOpen", breakersOpen);
        point.put("components", components);
        return point;
    }

    private static long toLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static double toDouble(Object o) { return o instanceof Number n ? n.doubleValue() : 0d; }
    private static int toneRank(String t) { return "down".equals(t) ? 3 : "warn".equals(t) ? 2 : 1; }
    private static String worse(String a, String b) { return toneRank(a) >= toneRank(b) ? a : b; }

    private Map<String, Object> build() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());

        // 1. DLQ depths
        Map<String, Object> dlq = dlqReplayService.depths();
        out.put("dlq", dlq);

        // 2. Circuit breakers — pure Resilience4j state + a SEPARATE reachability probe.
        List<Map<String, Object>> breakers = new ArrayList<>();
        boolean erpReachable = true;
        CircuitBreakerRegistry registry = circuitBreakerRegistry.getIfAvailable();
        if (registry != null) {
            for (CircuitBreaker cb : registry.getAllCircuitBreakers()) {
                CircuitBreaker.Metrics m = cb.getMetrics();
                String nameLower = cb.getName().toLowerCase();

                boolean reachable;
                boolean shallow;
                if (nameLower.contains("driver")) {
                    reachable = actuatorUp(driverServiceUrl); shallow = false;
                } else if (nameLower.contains("erp") || nameLower.contains("adapter")) {
                    reachable = actuatorUp(erpAdapterUrl); shallow = false;
                    erpReachable = reachable;
                } else if (nameLower.contains("auth")) {
                    reachable = isTcpReachable(authServerUrl); shallow = true;
                } else if (nameLower.contains("route")) {
                    reachable = isTcpReachable(osrmUrl); shallow = true;
                } else {
                    reachable = true; shallow = true;
                }

                Map<String, Object> b = new LinkedHashMap<>();
                b.put("name", cb.getName());
                b.put("state", cb.getState().name());
                b.put("reachable", reachable);
                b.put("shallow", shallow);
                b.put("failureRate", m.getFailureRate());
                b.put("bufferedCalls", m.getNumberOfBufferedCalls());
                b.put("failedCalls", m.getNumberOfFailedCalls());
                b.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
                breakers.add(b);
            }
        }
        out.put("circuitBreakers", breakers);

        // 3. Database health
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("reachable", isDatabaseReachable());
        out.put("db", db);

        // 4. ERP sync — the accurate signal, from orders.odoo_sync_status (not DLQ depth).
        out.put("erpSync", buildErpSync());

        // 5. Back-compat ERP block consumed by older clients.
        Map<String, Object> erp = new LinkedHashMap<>();
        long failedCount = orderRepo.countByOdooSyncStatus("SYNC_FAILED");
        erp.put("reachable", erpReachable);
        erp.put("pendingSyncFailures", failedCount);
        out.put("erp", erp);

        return out;
    }

    private Map<String, Object> buildErpSync() {
        Map<String, Object> erpSync = new LinkedHashMap<>();
        long failed = orderRepo.countByOdooSyncStatus("SYNC_FAILED");
        long inProgress = orderRepo.countByOdooSyncStatus("PENDING_SYNC");

        List<Order> failures = orderRepo.findTop50ByOdooSyncStatusOrderByUpdatedAtAsc("SYNC_FAILED");
        List<Map<String, Object>> failureList = new ArrayList<>();
        Long oldestAgeMinutes = null;
        for (Order o : failures) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("orderId", o.getId());
            f.put("blNumber", o.getBlNumber());
            f.put("erpRef", o.getErpExternalRef() != null ? o.getErpExternalRef() : o.getErpOrderId());
            f.put("lastSyncOp", o.getLastSyncOp());
            f.put("lastSyncError", o.getLastSyncError());
            f.put("retryCount", o.getSyncRetryCount());
            f.put("stuckSince", o.getUpdatedAt() != null ? o.getUpdatedAt().toString() : null);
            failureList.add(f);
            if (oldestAgeMinutes == null && o.getUpdatedAt() != null) {
                oldestAgeMinutes = ChronoUnit.MINUTES.between(o.getUpdatedAt(), LocalDateTime.now());
            }
        }

        erpSync.put("failed", failed);
        erpSync.put("inProgress", inProgress);
        erpSync.put("oldestFailedAgeMinutes", oldestAgeMinutes);
        erpSync.put("failures", failureList);
        return erpSync;
    }

    private boolean isDatabaseReachable() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(1);
        } catch (Exception e) {
            return false;
        }
    }

    /** Real health: 200 from {base}/actuator/health with status UP. */
    private boolean actuatorUp(String base) {
        if (base == null || base.isBlank()) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + "/actuator/health"))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body() != null && resp.body().contains("\"status\":\"UP\"");
        } catch (Exception e) {
            return false;
        }
    }

    /** Shallow check: a port is accepting connections. Used where actuator isn't available. */
    private boolean isTcpReachable(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) return false;
        try {
            URL url = new URL(urlStr);
            int port = url.getPort();
            if (port == -1) port = url.getDefaultPort();
            if (port == -1) port = url.getProtocol().equalsIgnoreCase("https") ? 443 : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(url.getHost(), port), 400);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
