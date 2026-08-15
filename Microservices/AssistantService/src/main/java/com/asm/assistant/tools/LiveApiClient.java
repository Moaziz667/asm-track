package com.asm.assistant.tools;

import com.asm.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only client for the live ASM APIs. Two hard rules:
 * <ul>
 *   <li><b>Read-on-behalf-of-user</b>: forwards the caller's {@code Authorization} bearer and
 *       {@code X-Company-Id}, so downstream RBAC + tenant isolation apply exactly as if the user
 *       called directly — the assistant can never read what the user couldn't.</li>
 *   <li><b>Fail honest</b>: any error (unreachable, 4xx/5xx, timeout) returns {@link Optional#empty()};
 *       the orchestrator then says the live state is unknown rather than inventing it.</li>
 * </ul>
 * Only GETs are exposed here. No write/mutating call is reachable through this client.
 */
@Component
@Slf4j
public class LiveApiClient {

    private final RestClient delivery;
    private final RestClient driver;

    public LiveApiClient(
            @Value("${delivery.service.url:http://delivery-service:8082}") String deliveryUrl,
            @Value("${driver.service.url:http://driver-service:8086}") String driverUrl,
            @Value("${assistant.tools.timeout-ms:5000}") long timeoutMs) {
        this.delivery = build(deliveryUrl, timeoutMs);
        this.driver = build(driverUrl, timeoutMs);
    }

    private RestClient build(String baseUrl, long timeoutMs) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    public Optional<Map<String, Object>> getDelivery(String id)      { return get(delivery, "/api/v1/admin/deliveries/{id}", id); }
    public Optional<Map<String, Object>> getSlaTimeline(String id)   { return get(delivery, "/api/v1/admin/deliveries/{id}/sla-timeline", id); }
    public Optional<Map<String, Object>> getReturn(String id)        { return get(delivery, "/api/v1/admin/returns/{id}", id); }
    public Optional<Map<String, Object>> getRoute(String id)         { return get(delivery, "/api/v1/admin/routes/{id}", id); }
    public Optional<Map<String, Object>> getRouteDriverLocation(String id) { return get(delivery, "/api/v1/admin/routes/{id}/driver-location", id); }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> get(RestClient rc, String uri, String id) {
        try {
            String auth = currentAuthorization();
            UUID tenant = TenantContext.get();
            Map<String, Object> body = rc.get()
                    .uri(uri, id)
                    .headers(h -> {
                        if (auth != null) h.set("Authorization", auth);
                        if (tenant != null) h.set("X-Company-Id", tenant.toString());
                    })
                    .retrieve()
                    .body(Map.class);
            return Optional.ofNullable(body);
        } catch (Exception e) {
            // Fail honest — never fabricate live state.
            log.warn("Live API {} [{}] unavailable: {}", uri, id, e.getMessage());
            return Optional.empty();
        }
    }

    private String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader("Authorization");
        }
        return null;
    }
}
