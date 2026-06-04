package com.asm.driver.client;

import com.asm.driver.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakAdminClient {

    private final RestTemplate restTemplate;

    @Value("${kc.issuer-uri:http://keycloak:8080/realms/asm}")
    private String issuerUri;

    @Value("${auth.client.id:driver-service}")
    private String clientId;

    @Value("${auth.client.secret}")
    private String clientSecret;

    private String getAdminUrl() {
        return issuerUri.replace("/realms/asm", "/admin/realms/asm");
    }

    private String getToken() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    issuerUri + "/protocol/openid-connect/token",
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    Map.class);
            return (String) resp.getBody().get("access_token");
        } catch (HttpClientErrorException e) {
            log.error("Failed to get Keycloak service token: {}", e.getResponseBodyAsString());
            throw AppException.internal("IAM Auth Failed");
        }
    }

    public String createDriver(String appUserId, String email, String phone) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. Create driver user (appUserId as username)
        Map<String, Object> userPayload = Map.of(
                "username", appUserId,
                "email", email,
                "enabled", true,
                "attributes", Map.of(
                        "app_user_id", List.of(appUserId),
                        "phone", List.of(phone),
                        "role", List.of("DRIVER")
                )
        );

        try {
            restTemplate.postForEntity(getAdminUrl() + "/users", new HttpEntity<>(userPayload, headers), String.class);
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Driver already exists in Keycloak: {}", appUserId);
        } catch (HttpClientErrorException e) {
            log.error("Failed to create driver in Keycloak: {}", e.getResponseBodyAsString());
            throw AppException.internal("IAM Provisioning Failed");
        }

        // 2. Get generated ID
        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + appUserId + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() == null || searchResp.getBody().isEmpty()) {
            throw AppException.internal("User not found after creation");
        }
        Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
        String kcUserId = (String) user.get("id");

        // 3. Assign DRIVER role
        assignRole(kcUserId, "DRIVER", headers);

        return kcUserId;
    }

    private void assignRole(String kcUserId, String role, HttpHeaders headers) {
        ResponseEntity<Map> roleResp;
        try {
            roleResp = restTemplate.exchange(
                    getAdminUrl() + "/roles/" + role,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
        } catch (HttpClientErrorException e) {
            log.error("Failed to fetch role {} from Keycloak: {}", role, e.getResponseBodyAsString());
            return;
        }

        Map<String, Object> roleRepr = roleResp.getBody();
        if (roleRepr != null) {
            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                        HttpMethod.POST,
                        new HttpEntity<>(List.of(roleRepr), headers),
                        String.class);
            } catch (HttpClientErrorException e) {
                log.error("Failed to assign role to user in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    public void triggerInviteEmail(String appUserId) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        
        String kcUserId = getUserIdByUsername(appUserId, headers);
        if (kcUserId != null) {
            triggerInvite(kcUserId, headers);
        }
    }

    public void deleteDriver(String appUserId) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        
        String kcUserId = getUserIdByUsername(appUserId, headers);
        if (kcUserId != null) {
            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(headers),
                        Void.class);
                log.info("Successfully deleted user in Keycloak: {}", appUserId);
            } catch (HttpClientErrorException e) {
                log.error("Failed to delete user in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    private String getUserIdByUsername(String username, HttpHeaders headers) {
        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + username + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
            Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
            return (String) user.get("id");
        }
        return null;
    }

    private void triggerInvite(String kcUserId, HttpHeaders headers) {
        try {
            restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId + "/execute-actions-email",
                    HttpMethod.PUT,
                    new HttpEntity<>(List.of("UPDATE_PASSWORD"), headers),
                    String.class);
        } catch (HttpClientErrorException e) {
            log.error("Failed to trigger invite email: {}", e.getResponseBodyAsString());
        }
    }

    public void setUserEnabled(String appUserId, boolean enabled) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String kcUserId = getUserIdByUsername(appUserId, headers);
        if (kcUserId != null) {
            Map<String, Object> updatePayload = Map.of("enabled", enabled);
            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId,
                        HttpMethod.PUT,
                        new HttpEntity<>(updatePayload, headers),
                        String.class);
                log.info("Successfully updated enabled status to {} in Keycloak for driver username: {}", enabled, appUserId);
            } catch (HttpClientErrorException e) {
                log.error("Failed to update user enabled status in Keycloak: {}", e.getResponseBodyAsString());
            }
        } else {
            log.warn("User not found in Keycloak to update enabled status: {}", appUserId);
        }
    }

    public void disableDriver(String appUserId) {
        setUserEnabled(appUserId, false);
    }

    public void enableDriver(String appUserId) {
        setUserEnabled(appUserId, true);
    }

    public void resetPassword(String appUserId, String newPassword) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String kcUserId = getUserIdByUsername(appUserId, headers);
        if (kcUserId == null) {
            throw AppException.notFound("Driver not found in Keycloak");
        }

        Map<String, Object> credential = Map.of(
                "type", "password",
                "value", newPassword,
                "temporary", false
        );

        try {
            restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId + "/reset-password",
                    HttpMethod.PUT,
                    new HttpEntity<>(credential, headers),
                    Void.class);
            log.info("Successfully updated password in Keycloak for driver: {}", appUserId);
        } catch (HttpClientErrorException e) {
            log.error("Failed to reset password in Keycloak: {}", e.getResponseBodyAsString());
            throw AppException.internal("Failed to set credentials in Keycloak");
        }
    }

    public void updateDriverEmail(String appUserId, String email) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String kcUserId = getUserIdByUsername(appUserId, headers);
        if (kcUserId == null) {
            log.warn("User not found in Keycloak for email update: {}", appUserId);
            return;
        }

        Map<String, Object> updatePayload = Map.of("email", email);

        try {
            restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId,
                    HttpMethod.PUT,
                    new HttpEntity<>(updatePayload, headers),
                    Void.class);
        } catch (HttpClientErrorException e) {
            log.error("Failed to update user email in Keycloak: {}", e.getResponseBodyAsString());
            throw AppException.internal("Failed to update email in Keycloak");
        }
    }

    public void forceLogout(String appUserId) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        String kcUserId = getUserIdByUsername(appUserId, headers);
        if (kcUserId != null) {
            try {
                restTemplate.postForEntity(
                        getAdminUrl() + "/users/" + kcUserId + "/logout",
                        new HttpEntity<>(null, headers),
                        Void.class);
                log.info("Successfully triggered force logout in Keycloak for driver username: {}", appUserId);
            } catch (HttpClientErrorException e) {
                log.error("Failed to force logout driver in Keycloak: {}", e.getResponseBodyAsString());
                throw AppException.internal("Failed to force logout in Keycloak");
            }
        } else {
            log.warn("Driver user not found in Keycloak to force logout: {}", appUserId);
        }
    }

    public Map<String, Object> getUserDetails(String appUserId) {
        try {
            String token = getToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);

            ResponseEntity<List> searchResp = restTemplate.exchange(
                    getAdminUrl() + "/users?username=" + appUserId + "&exact=true",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    List.class);

            if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
                return (Map<String, Object>) searchResp.getBody().get(0);
            }
        } catch (Exception e) {
            log.error("Failed to get Keycloak user details for driver appUserId={}: {}", appUserId, e.getMessage());
        }
        return null;
    }
}
