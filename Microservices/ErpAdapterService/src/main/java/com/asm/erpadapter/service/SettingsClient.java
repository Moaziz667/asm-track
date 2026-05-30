package com.asm.erpadapter.service;

import com.asm.erpadapter.dto.SystemSettingsDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class SettingsClient {

    @Value("${AUTH_SERVER_URL:http://auth-server:8089}")
    private String authServerUrl;

    @Value("${CLIENT_ID:erp-adapter}")
    private String clientId;

    @Value("${CLIENT_SECRET}")
    private String clientSecret;

    // Use app-backend DNS since ErpAdapter is internal
    @Value("${APP_BACKEND_URL:http://app-backend:8080}")
    private String appBackendUrl;

    private final RestTemplate restTemplate = createRestTemplate();

    private RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = 
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); // 5 seconds
        factory.setReadTimeout(5000);    // 5 seconds
        return new RestTemplate(factory);
    }
    
    // Simple Cache: 60 seconds
    private final AtomicReference<SystemSettingsDto> cachedSettings = new AtomicReference<>();
    private final AtomicLong cacheTimestamp = new AtomicLong(0);

    public SystemSettingsDto getSettings() {
        long now = System.currentTimeMillis();
        if (cachedSettings.get() != null && (now - cacheTimestamp.get()) < 60_000) {
            return cachedSettings.get();
        }

        try {
            String token = fetchServiceToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            
            String url = appBackendUrl + "/api/settings/internal/erp"; // Note: adjusted path to match what we put in ApiGateway or Controller
            
            ResponseEntity<SystemSettingsDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), SystemSettingsDto.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                cachedSettings.set(response.getBody());
                cacheTimestamp.set(now);
                return response.getBody();
            }
        } catch (Exception e) {
            log.error("Failed to fetch ERP settings from AppBackend", e);
        }
        
        // Return fallback if failure
        return cachedSettings.get() != null ? cachedSettings.get() : new SystemSettingsDto("NONE", null);
    }

    private String fetchServiceToken() {
        String url = authServerUrl + "/oauth2/token";
        
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/x-www-form-urlencoded");
        
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("access_token");
            }
        } catch (Exception e) {
            log.error("Failed to fetch service token from auth-server", e);
        }
        throw new RuntimeException("Could not fetch service token");
    }
}
