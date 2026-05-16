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
    private final String authServerUrl;
    private final String clientId;
    private final String clientSecret;

    private String  cachedToken;
    private Instant tokenExpiresAt = Instant.MIN;

    private record CacheEntry(CompanyErpConfig config, Instant expiresAt) {}
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long TTL_SECONDS = 300;

    public CompanyConfigResolver(
            @Value("${delivery.service.url:http://delivery-service:8082}") String deliveryServiceUrl,
            @Value("${auth.server.url}") String authServerUrl,
            @Value("${auth.client.id}") String clientId,
            @Value("${auth.client.secret}") String clientSecret) {

        this.deliveryServiceUrl = deliveryServiceUrl;
        this.authServerUrl      = authServerUrl;
        this.clientId           = clientId;
        this.clientSecret       = clientSecret;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    private synchronized String getServiceToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) return cachedToken;
        org.springframework.util.MultiValueMap<String, String> params = new org.springframework.util.LinkedMultiValueMap<>();
        params.add("grant_type",    "client_credentials");
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<Map> resp = new RestTemplate().exchange(
                authServerUrl + "/oauth2/token", HttpMethod.POST,
                new HttpEntity<>(params, h), Map.class);
        Map<String, Object> body = resp.getBody();
        cachedToken    = (String) body.get("access_token");
        int expiresIn  = ((Number) body.get("expires_in")).intValue();
        tokenExpiresAt = Instant.now().plusSeconds(expiresIn - 30);
        return cachedToken;
    }

    public CompanyErpConfig resolve(UUID companyId) {
        CacheEntry entry = cache.get(companyId);
        if (entry != null && Instant.now().isBefore(entry.expiresAt())) {
            return entry.config();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getServiceToken());

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
