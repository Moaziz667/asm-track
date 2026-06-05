package com.asm.appbackend.client;

import com.asm.appbackend.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakAdminClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${kc.issuer-uri:http://keycloak:8080/realms/asm}")
    private String issuerUri;

    @Value("${auth.client.id:app-backend}")
    private String clientId;

    @Value("${auth.client.secret}")
    private String clientSecret;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.MIN;

    private String getAdminUrl() {
        return issuerUri.replace("/realms/asm", "/admin/realms/asm");
    }

    private String userSearchUrl(String email) {
        return UriComponentsBuilder.fromHttpUrl(getAdminUrl() + "/users")
                .queryParam("username", email)
                .queryParam("exact", "true")
                .build()
                .toUriString();
    }

    public synchronized String getServiceToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    issuerUri + "/protocol/openid-connect/token",
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    MAP_TYPE);
            Map<String, Object> body = resp.getBody();
            cachedToken = (String) body.get("access_token");
            int expiresIn = body.get("expires_in") instanceof Number n ? n.intValue() : 300;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn - 30);
            return cachedToken;
        } catch (HttpClientErrorException e) {
            log.error("Failed to get Keycloak service token: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "IAM Auth Failed");
        }
    }

    private HttpHeaders bearerHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(getServiceToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private List<Map<String, Object>> searchByEmail(String email) {
        ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                userSearchUrl(email), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders()), LIST_OF_MAPS);
        return resp.getBody() != null ? resp.getBody() : Collections.emptyList();
    }

    public String createUser(String email, String role, String appUserId, String password) {
        HttpHeaders headers = bearerHeaders();

        List<Map<String, Object>> existing = searchByEmail(email);
        String kcUserId;

        if (!existing.isEmpty()) {
            kcUserId = (String) existing.get(0).get("id");
            log.info("User already exists in Keycloak (idempotency): email={}", email);
        } else {
            Map<String, Object> userPayload = (password != null && !password.isBlank())
                    ? Map.of("username", email, "email", email, "enabled", true,
                             "attributes", Map.of("app_user_id", List.of(appUserId)),
                             "credentials", List.of(Map.of("type", "password", "value", password, "temporary", true)))
                    : Map.of("username", email, "email", email, "enabled", true,
                             "attributes", Map.of("app_user_id", List.of(appUserId)));

            try {
                restTemplate.postForEntity(
                        getAdminUrl() + "/users", new HttpEntity<>(userPayload, headers), String.class);
            } catch (HttpClientErrorException.Conflict e) {
                log.warn("User conflict on create (idempotency): {}", email);
            } catch (HttpClientErrorException e) {
                log.error("Failed to create user in Keycloak: {}", e.getResponseBodyAsString());
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "IAM Provisioning Failed");
            }

            List<Map<String, Object>> created = searchByEmail(email);
            if (created.isEmpty()) {
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "User not found after creation");
            }
            kcUserId = (String) created.get(0).get("id");
        }

        assignRole(kcUserId, role, headers);
        return kcUserId;
    }

    private void assignRole(String kcUserId, String role, HttpHeaders headers) {
        ResponseEntity<Map<String, Object>> roleResp;
        try {
            roleResp = restTemplate.exchange(
                    getAdminUrl() + "/roles/" + role,
                    HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
        } catch (HttpClientErrorException e) {
            log.error("Failed to fetch role {} from Keycloak: {}", role, e.getResponseBodyAsString());
            return;
        }
        Map<String, Object> roleRepr = roleResp.getBody();
        if (roleRepr != null) {
            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                        HttpMethod.POST, new HttpEntity<>(List.of(roleRepr), headers), String.class);
            } catch (HttpClientErrorException e) {
                log.error("Failed to assign role to user in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    public void setUserEnabled(String email, boolean enabled) {
        HttpHeaders headers = bearerHeaders();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId,
                    HttpMethod.PUT, new HttpEntity<>(Map.of("enabled", enabled), headers), String.class);
            log.info("Updated enabled={} in Keycloak for user: {}", enabled, email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to update enabled status in Keycloak: {}", e.getResponseBodyAsString());
        }
    }

    public void enableUser(String email)  { setUserEnabled(email, true); }
    public void disableUser(String email) { setUserEnabled(email, false); }

    public void updateUserEmail(String oldEmail, String newEmail) {
        HttpHeaders headers = bearerHeaders();
        List<Map<String, Object>> users = searchByEmail(oldEmail);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId,
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("email", newEmail, "username", newEmail), headers),
                    Void.class);
            log.info("Updated email {} → {} in Keycloak", oldEmail, newEmail);
        } catch (HttpClientErrorException e) {
            log.error("Failed to update email in Keycloak: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update email in Keycloak");
        }
    }

    public void setUserRole(String email, String role) {
        HttpHeaders headers = bearerHeaders();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) { log.error("User not found for role update in Keycloak: {}", email); return; }
        String kcUserId = (String) users.get(0).get("id");

        ResponseEntity<List<Map<String, Object>>> mappingsResp = restTemplate.exchange(
                getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                HttpMethod.GET, new HttpEntity<>(headers), LIST_OF_MAPS);

        List<Map<String, Object>> currentRoles = mappingsResp.getBody();
        if (currentRoles != null) {
            List<Map<String, Object>> toRemove = currentRoles.stream()
                    .filter(r -> List.of("ADMIN", "DISPATCHER", "MANAGER")
                            .contains(((String) r.get("name")).toUpperCase()))
                    .toList();
            if (!toRemove.isEmpty()) {
                try {
                    restTemplate.exchange(
                            getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                            HttpMethod.DELETE, new HttpEntity<>(toRemove, headers), Void.class);
                } catch (HttpClientErrorException e) {
                    log.error("Failed to remove old roles in Keycloak: {}", e.getResponseBodyAsString());
                }
            }
        }
        assignRole(kcUserId, role, headers);
    }

    public void triggerPasswordResetEmail(String email) {
        HttpHeaders headers = bearerHeaders();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId + "/execute-actions-email",
                    HttpMethod.PUT, new HttpEntity<>(List.of("UPDATE_PASSWORD"), headers), String.class);
            log.info("Triggered UPDATE_PASSWORD email for: {}", email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to trigger password reset: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to trigger password reset email");
        }
    }

    public void forceLogout(String email) {
        HttpHeaders headers = bearerHeaders();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restTemplate.postForEntity(
                    getAdminUrl() + "/users/" + kcUserId + "/logout",
                    new HttpEntity<>(null, headers), Void.class);
            log.info("Force-logged out user in Keycloak: {}", email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to force logout user in Keycloak: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to force logout user");
        }
    }

    public void deleteUser(String email) {
        HttpHeaders headers = bearerHeaders();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId,
                    HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
            log.info("Deleted user in Keycloak: {}", email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to delete user in Keycloak: {}", e.getResponseBodyAsString());
        }
    }

    public Map<String, Object> getUserDetails(String email) {
        try {
            List<Map<String, Object>> users = searchByEmail(email);
            return users.isEmpty() ? null : users.get(0);
        } catch (Exception e) {
            log.error("Failed to get Keycloak user details for email={}: {}", email, e.getMessage());
            return null;
        }
    }

    public List<String> getUserRoles(String kcUserId) {
        try {
            HttpHeaders headers = bearerHeaders();
            ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                    HttpMethod.GET, new HttpEntity<>(headers), LIST_OF_MAPS);
            List<Map<String, Object>> body = resp.getBody();
            if (body != null) {
                return body.stream().map(r -> ((String) r.get("name")).toUpperCase()).toList();
            }
        } catch (Exception e) {
            log.error("Failed to get Keycloak roles for kcUserId={}: {}", kcUserId, e.getMessage());
        }
        return Collections.emptyList();
    }
}
