package com.asm.driver.service;

import com.asm.driver.dto.response.ActiveMissionsDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class DeliveryServiceWebClient {

    private final RestTemplate restTemplate;

    @Value("${delivery.service.url:http://localhost:8082}")
    private String deliveryServiceUrl;

    @Value("${auth.server.url:http://localhost:8089}")
    private String authServerUrl;

    @Value("${auth.client.id:driver-service}")
    private String clientId;

    @Value("${auth.client.secret:7gc13nP8d87F3MzGZxT5qV8qtjqnkPTpKEQwdhjCkAk=}")
    private String clientSecret;

    private String cachedToken;
    private Instant tokenExpiresAt = Instant.MIN;

    private Map<UUID, ActiveMissionsDTO> cachedMissions;
    private Instant cacheExpiresAt = Instant.MIN;

    public DeliveryServiceWebClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public synchronized Map<UUID, ActiveMissionsDTO> getActiveMissions() {
        if (cachedMissions != null && Instant.now().isBefore(cacheExpiresAt)) {
            return cachedMissions;
        }

        try {
            String url = deliveryServiceUrl + "/internal/deliveries/active-missions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(getServiceToken());

            ResponseEntity<Map<UUID, ActiveMissionsDTO>> resp = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<UUID, ActiveMissionsDTO>>() {});

            Map<UUID, ActiveMissionsDTO> body = resp.getBody();
            cachedMissions = body != null ? body : Collections.emptyMap();
            cacheExpiresAt = Instant.now().plusSeconds(5); // Cache for 5 seconds
            return cachedMissions;
        } catch (Exception e) {
            log.warn("Failed to retrieve active driver missions from Delivery Service: {}. Serving stale cache.", e.getMessage());
            return cachedMissions != null ? cachedMissions : Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized String getServiceToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }

        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "client_credentials");
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    authServerUrl + "/protocol/openid-connect/token",
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    Map.class);

            Map<String, Object> body = resp.getBody();
            if (body != null && body.containsKey("access_token")) {
                cachedToken = (String) body.get("access_token");
                int expiresIn = body.containsKey("expires_in") ? ((Number) body.get("expires_in")).intValue() : 3600;
                tokenExpiresAt = Instant.now().plusSeconds(expiresIn - 30); // 30s buffer
                log.debug("Acquired new service token for Delivery Service calls.");
                return cachedToken;
            }
        } catch (Exception e) {
            log.error("Failed to acquire service token from Auth Server: {}", e.getMessage());
        }

        return "";
    }
}
