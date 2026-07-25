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
 * sourced from {@code orders.erp_sync_status} — the accurate signal — not DLQ depth.
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

    /**
     * MULTI-TENANT SPLIT: the snapshot used to be ONE shared map, but it mixes two kinds of data —
     * platform infrastructure (breakers, DLQ depths, DB reachability: tenant-agnostic) and ERP sync
     * state read from {@code orders} (tenant-scoped: BL numbers, ERP refs, error text). A single
     * shared map either showed the empty {@code public}-schema counts (scheduled refresh has no
     * TenantContext) or — worse — leaked whichever tenant's failure list happened to build it to
     * every other tenant. Now the scheduler refreshes only the global part, and the tenant part is
     * computed lazily per tenant on the caller's request thread (which carries the TenantContext, so
     * Hibernate routes the queries to the right schema), with a short per-tenant cache and history.
     */
    private volatile Map<String, Object> globalSnapshot;

    private record TenantErpEntry(Map<String, Object> erpSync, long failedCount, long atMs) {}
    private final Map<java.util.UUID, TenantErpEntry> erpByTenant = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TENANT_ERP_TTL_MS = 10_000;

    /** Rolling per-tenant history of compact health points for the console's trend sparklines.
     *  In-memory only — resets on restart, which is fine for an operational at-a-glance view. */
    private static final int HISTORY_CAPACITY = 360; // ~1 hour at the ~10s console poll cadence
    private static final long HISTORY_MIN_SPACING_MS = 9_000;
    private final Map<java.util.UUID, java.util.Deque<Map<String, Object>>> historyByTenant =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Snapshot for the CURRENT tenant: shared infra signals + this tenant's ERP sync state only. */
    public Map<String, Object> current() {
        Map<String, Object> global = globalSnapshot;
        if (global == null) {
            global = buildGlobal();
            globalSnapshot = global;
        }
        Map<String, Object> out = new LinkedHashMap<>(global);
        java.util.UUID tenant = com.asm.delivery.security.TenantContext.get();
        if (tenant != null) {
            TenantErpEntry entry = freshTenantErp(tenant);
            out.put("erpSync", entry.erpSync());
            Map<String, Object> erp = new LinkedHashMap<>();
            Object baseErp = global.get("erp");
            if (baseErp instanceof Map<?, ?> m) m.forEach((k, v) -> erp.put(String.valueOf(k), v));
            erp.put("pendingSyncFailures", entry.failedCount());
            out.put("erp", erp);
            recordHistory(tenant, out);
        }
        return out;
    }

    /** Rolling history for the CURRENT tenant, oldest → newest (a copy, safe to serialize off-thread). */
    public List<Map<String, Object>> history() {
        java.util.UUID tenant = com.asm.delivery.security.TenantContext.get();
        if (tenant == null) return List.of();
        java.util.Deque<Map<String, Object>> h = historyByTenant.get(tenant);
        if (h == null) return List.of();
        synchronized (h) {
            return new ArrayList<>(h);
        }
    }

    /** Scheduled refresh keeps only the tenant-agnostic infra probes warm (no TenantContext here). */
    @Scheduled(fixedDelay = 10_000)
    public void refresh() {
        try {
            globalSnapshot = buildGlobal();
        } catch (Exception e) {
            log.warn("System health snapshot refresh failed: {}", e.getMessage());
        }
    }

    private TenantErpEntry freshTenantErp(java.util.UUID tenant) {
        TenantErpEntry cached = erpByTenant.get(tenant);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.atMs()) < TENANT_ERP_TTL_MS) return cached;
        Map<String, Object> erpSync = buildErpSync();
        long failed = toLong(erpSync.get("failed"));
        TenantErpEntry entry = new TenantErpEntry(erpSync, failed, now);
        erpByTenant.put(tenant, entry);
        return entry;
    }

    private void recordHistory(java.util.UUID tenant, Map<String, Object> snap) {
        java.util.Deque<Map<String, Object>> h =
                historyByTenant.computeIfAbsent(tenant, t -> new java.util.ArrayDeque<>(HISTORY_CAPACITY + 8));
        synchronized (h) {
            Map<String, Object> last = h.peekLast();
            if (last != null && System.currentTimeMillis() - toLong(last.get("t")) < HISTORY_MIN_SPACING_MS) {
                return; // console polls can be more frequent than the intended cadence — don't flood
            }
            h.addLast(buildHistoryPoint(snap));
            while (h.size() > HISTORY_CAPACITY) h.removeFirst();
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

    /** Tenant-agnostic infra probes only — NEVER add order/tenant data here (runs with no TenantContext). */
    private Map<String, Object> buildGlobal() {
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

        // 4. ERP sync data is TENANT-SCOPED and is overlaid per tenant in current() — the scheduled
        //    thread has no TenantContext, so querying orders here would read the empty public schema
        //    (and caching one tenant's failure list here would leak it to every other tenant).
        out.put("erpSync", Map.of("failed", 0L, "inProgress", 0L, "failures", List.of()));

        // 5. Back-compat ERP block: only the tenant-agnostic reachability lives in the global part;
        //    pendingSyncFailures is overlaid per tenant in current().
        Map<String, Object> erp = new LinkedHashMap<>();
        erp.put("reachable", erpReachable);
        erp.put("pendingSyncFailures", 0L);
        out.put("erp", erp);

        return out;
    }

    private Map<String, Object> buildErpSync() {
        Map<String, Object> erpSync = new LinkedHashMap<>();
        long failed = orderRepo.countByErpSyncStatus("SYNC_FAILED");
        long inProgress = orderRepo.countByErpSyncStatus("PENDING_SYNC");

        List<Order> failures = orderRepo.findTop50ByErpSyncStatusOrderByUpdatedAtAsc("SYNC_FAILED");
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
