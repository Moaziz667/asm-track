package com.asm.erpadapter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class CompanyConfigResolver {

    private final RestTemplate restTemplate;
    private final String deliveryServiceUrl;
    private final String internalSecret;

    private record CacheEntry(CompanyErpConfig config, Instant expiresAt) {}
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long TTL_SECONDS = 300; // 5 minutes

    public CompanyConfigResolver(
            @Value("${delivery.service.url:http://delivery-service:8082}") String deliveryServiceUrl,
            @Value("${internal.secret:asm-internal-2026}") String internalSecret) {

        this.deliveryServiceUrl = deliveryServiceUrl;
        this.internalSecret     = internalSecret;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    public CompanyErpConfig resolve(UUID companyId) {
        CacheEntry entry = cache.get(companyId);
        if (entry != null && Instant.now().isBefore(entry.expiresAt())) {
            return entry.config();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Secret", internalSecret);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    deliveryServiceUrl + "/internal/companies/" + companyId + "/erp-config",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body == null) return noERP();

            CompanyErpConfig config = new CompanyErpConfig(
                    str(body, "erpType", "NONE"),
                    str(body, "apiUrl",  ""),
                    str(body, "apiKey",  ""),
                    str(body, "dbName",  ""),
                    str(body, "username", "admin"),
                    intVal(body, "uid", 1)
            );
            cache.put(companyId, new CacheEntry(config, Instant.now().plusSeconds(TTL_SECONDS)));
            return config;

        } catch (Exception e) {
            log.warn("Failed to resolve ERP config for company {}: {}", companyId, e.getMessage());
            return noERP();
        }
    }

    private static CompanyErpConfig noERP() {
        return new CompanyErpConfig("NONE", "", "", "", "", 1);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : def;
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
