package com.asm.assistant.tools;

import com.asm.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only client for the live ASM APIs. Two hard rules:
 * <ul>
 *   <li><b>Read-on-behalf-of-user</b>: forwards the caller's {@code Authorization} bearer and
 *       {@code X-Company-Id}, so downstream RBAC + tenant isolation apply exactly as if the user
 *       called directly — the assistant can never read what the user couldn't.</li>
 *   <li><b>Fail honest</b>: nothing is ever fabricated. A 4xx becomes {@link Status#NOT_FOUND} (no
 *       such entity, or a malformed reference) and anything else — unreachable, 5xx, timeout —
 *       {@link Status#UNAVAILABLE}, so the answer can say which of the two actually happened.</li>
 * </ul>
 *
 * <p>That distinction also guards the circuit breaker: "no such delivery" is a normal business
 * answer, not a fault. Counting it as one meant a single mistyped reference could open the breaker
 * and disable live lookups for every later question.
 *
 * <p>Only GETs are exposed here. No write/mutating call is reachable through this client.
 */
@Component
@Slf4j
public class LiveApiClient {

    /** Outcome of a live read: found, genuinely absent, or the source could not answer. */
    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public record LiveLookup(Status status, Map<String, Object> body) {
        static LiveLookup found(Map<String, Object> b) { return new LiveLookup(Status.FOUND, b); }
        static final LiveLookup NOT_FOUND = new LiveLookup(Status.NOT_FOUND, Map.of());
        static final LiveLookup UNAVAILABLE = new LiveLookup(Status.UNAVAILABLE, Map.of());
    }

    private final RestClient delivery;
    private final RestClient driver;
    private final CircuitBreaker breaker;

    public LiveApiClient(
            @Value("${delivery.service.url:http://delivery-service:8082}") String deliveryUrl,
            @Value("${driver.service.url:http://driver-service:8086}") String driverUrl,
            @Value("${assistant.tools.timeout-ms:5000}") long timeoutMs,
            CircuitBreakerRegistry breakerRegistry) {
        this.delivery = build(deliveryUrl, timeoutMs);
        this.driver = build(driverUrl, timeoutMs);
        this.breaker = breakerRegistry.circuitBreaker("liveApi");
    }

    private RestClient build(String baseUrl, long timeoutMs) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    /**
     * Runs a tool from the catalogue. Collection endpoints answer with a JSON array, which is wrapped
     * under {@code items} so every result reaching the answer layer is a single object.
     */
    public LiveLookup call(LiveToolCatalog.Tool tool, String id) {
        return tool.needsId()
                ? get(delivery, tool.uri(), id)
                : getList(delivery, tool.uri());
    }

    public LiveLookup getDelivery(String id)      { return get(delivery, "/api/v1/admin/deliveries/{id}", id); }
    public LiveLookup getSlaTimeline(String id)   { return get(delivery, "/api/v1/admin/deliveries/{id}/sla-timeline", id); }
    public LiveLookup getReturn(String id)        { return get(delivery, "/api/v1/admin/returns/{id}", id); }
    public LiveLookup getRoute(String id)         { return get(delivery, "/api/v1/admin/routes/{id}", id); }
    public LiveLookup getRouteDriverLocation(String id) { return get(delivery, "/api/v1/admin/routes/{id}/driver-location", id); }

    @SuppressWarnings("unchecked")
    private LiveLookup get(RestClient rc, String uri, String id) {
        try {
            String auth = currentAuthorization();
            UUID tenant = TenantContext.get();
            // The 4xx is caught inside the decorated supplier so the breaker records it as a success:
            // the downstream service answered, it simply has no such entity.
            return breaker.decorateSupplier(() -> {
                try {
                    Map<String, Object> body = rc.get()
                            .uri(uri, id)
                            .headers(h -> {
                                if (auth != null) h.set("Authorization", auth);
                                if (tenant != null) h.set("X-Company-Id", tenant.toString());
                            })
                            .retrieve()
                            .body(Map.class);
                    return body == null ? LiveLookup.NOT_FOUND : LiveLookup.found(body);
                } catch (HttpClientErrorException e) {
                    log.info("Live API {} [{}] not found: {}", uri, id, e.getStatusCode());
                    return LiveLookup.NOT_FOUND;
                }
            }).get();
        } catch (Exception e) {
            // Fail honest — never fabricate live state.
            log.warn("Live API {} [{}] unavailable: {}", uri, id, e.getMessage());
            return LiveLookup.UNAVAILABLE;
        }
    }

    /** Same contract as {@link #get}, for endpoints returning a collection rather than an entity. */
    @SuppressWarnings("unchecked")
    private LiveLookup getList(RestClient rc, String uri) {
        try {
            String auth = currentAuthorization();
            UUID tenant = TenantContext.get();
            return breaker.decorateSupplier(() -> {
                try {
                    Object body = rc.get()
                            .uri(uri)
                            .headers(h -> {
                                if (auth != null) h.set("Authorization", auth);
                                if (tenant != null) h.set("X-Company-Id", tenant.toString());
                            })
                            .retrieve()
                            .body(Object.class);
                    if (body == null) return LiveLookup.NOT_FOUND;
                    // Some of these endpoints already answer with an object (stats, health).
                    return LiveLookup.found(body instanceof Map
                            ? (Map<String, Object>) body
                            : Map.of("items", body));
                } catch (HttpClientErrorException e) {
                    log.info("Live API {} not found: {}", uri, e.getStatusCode());
                    return LiveLookup.NOT_FOUND;
                }
            }).get();
        } catch (Exception e) {
            log.warn("Live API {} unavailable: {}", uri, e.getMessage());
            return LiveLookup.UNAVAILABLE;
        }
    }

    private String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader("Authorization");
        }
        return null;
    }
}
