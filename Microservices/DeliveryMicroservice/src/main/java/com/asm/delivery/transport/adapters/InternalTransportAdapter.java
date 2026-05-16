package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class InternalTransportAdapter implements TransportPort {

    private final RestTemplate restTemplate;
    private final String       baseUrl;
    private final String       authServerUrl;
    private final String       clientId;
    private final String       clientSecret;

    // Simple in-memory token cache
    private String  cachedToken;
    private Instant tokenExpiresAt = Instant.MIN;

    public InternalTransportAdapter(String baseUrl, String authServerUrl,
                                    String clientId, String clientSecret) {
        this.restTemplate  = new RestTemplate();
        this.baseUrl       = baseUrl;
        this.authServerUrl = authServerUrl;
        this.clientId      = clientId;
        this.clientSecret  = clientSecret;
    }

    @Override
    public List<DriverDTO> getAvailableDrivers() {
        try {
            ResponseEntity<List<DriverDTO>> resp = restTemplate.exchange(
                    baseUrl + "/internal/drivers/available",
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders()),
                    new ParameterizedTypeReference<>() {});
            List<DriverDTO> body = resp.getBody();
            return body != null ? body : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Driver Service getAvailableDrivers failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public DriverDTO getDriver(String driverId) {
        try {
            ResponseEntity<DriverDTO> resp = restTemplate.exchange(
                    baseUrl + "/internal/drivers/" + driverId,
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders()),
                    DriverDTO.class);
            return resp.getBody();
        } catch (Exception e) {
            log.warn("Driver Service getDriver({}) failed: {}", driverId, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean updateLocation(String driverId, double lat, double lng) {
        try {
            restTemplate.exchange(
                    baseUrl + "/internal/drivers/" + driverId + "/location",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("lat", lat, "lng", lng), bearerHeaders()),
                    Void.class);
            return true;
        } catch (Exception e) {
            log.warn("Driver Service updateLocation({}) failed: {}", driverId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean incrementStat(String driverId, String field) {
        try {
            restTemplate.exchange(
                    baseUrl + "/internal/drivers/" + driverId + "/stats/increment",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("field", field), bearerHeaders()),
                    Void.class);
            return true;
        } catch (Exception e) {
            log.warn("Driver Service incrementStat({}, {}) failed: {}", driverId, field, e.getMessage());
            return false;
        }
    }

    // ── Token management ──────────────────────────────────────────────────────

    private HttpHeaders bearerHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(getServiceToken());
        return h;
    }

    @SuppressWarnings("unchecked")
    private synchronized String getServiceToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",    "client_credentials");
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<Map> resp = restTemplate.exchange(
                authServerUrl + "/oauth2/token",
                HttpMethod.POST,
                new HttpEntity<>(params, h),
                Map.class);

        Map<String, Object> body = resp.getBody();
        cachedToken    = (String) body.get("access_token");
        int expiresIn  = ((Number) body.get("expires_in")).intValue();
        tokenExpiresAt = Instant.now().plusSeconds(expiresIn - 30); // 30s buffer
        log.debug("Service token acquired for clientId={} expiresIn={}s", clientId, expiresIn);
        return cachedToken;
    }
}
