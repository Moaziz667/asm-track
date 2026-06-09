package com.asm.appbackend.client;

import com.asm.appbackend.config.ServiceClientConfig;
import com.asm.appbackend.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the Keycloak Admin REST API. The {@code client_credentials} service token is
 * acquired/cached/refreshed by Spring Security's {@link OAuth2AuthorizedClientManager} (the shared
 * {@code asm-svc} registration) — no hand-rolled token cache. HTTP is done with {@link RestClient},
 * which raises the same {@link HttpClientErrorException} hierarchy the error handling below relies on.
 */
@Component
@Slf4j
public class KeycloakAdminClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    @Value("${kc.issuer-uri:http://keycloak:8080/realms/asm}")
    private String issuerUri;

    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                               OAuth2AuthorizedClientManager authorizedClientManager) {
        this.restClient = restClientBuilder.build();
        this.authorizedClientManager = authorizedClientManager;
    }

    private String getAdminUrl() {
        return issuerUri.replace("/realms/asm", "/admin/realms/asm");
    }

    private String userSearchUrl(String username) {
        return UriComponentsBuilder.fromHttpUrl(getAdminUrl() + "/users")
                .queryParam("username", username)
                .queryParam("exact", "true")
                .build()
                .toUriString();
    }

    private List<Map<String, Object>> searchByUserId(String appUserId) {
        List<Map<String, Object>> body = restClient.get()
                .uri(userSearchUrl(appUserId))  // Search by username (which is now appUserId)
                .header("Authorization", "Bearer " + getServiceToken())
                .retrieve()
                .body(LIST_OF_MAPS);
        return body != null ? body : Collections.emptyList();
    }

    private List<Map<String, Object>> searchByEmail(String email) {
        List<Map<String, Object>> body = restClient.get()
                .uri(userSearchUrl(email))  // Fallback search by email (for existing users)
                .header("Authorization", "Bearer " + getServiceToken())
                .retrieve()
                .body(LIST_OF_MAPS);
        return body != null ? body : Collections.emptyList();
    }

    /**
     * Search for a user by appUserId (new style: UUID as username) with fallback to email search.
     * Transition helper: old users (created before Option B refactor) are found by email;
     * new users have appUserId as username and are found directly.
     */
    private List<Map<String, Object>> searchUserWithFallback(String appUserId, String email) {
        List<Map<String, Object>> byId = searchByUserId(appUserId);
        if (!byId.isEmpty()) return byId;
        if (email != null && !email.isBlank()) {
            return searchByEmail(email);
        }
        return Collections.emptyList();
    }

    /** Acquires the SERVICE client_credentials token (managed/cached by Spring Security). */
    public String getServiceToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(ServiceClientConfig.REGISTRATION_ID)
                .principal(ServiceClientConfig.REGISTRATION_ID)
                .build();
        OAuth2AuthorizedClient client = authorizedClientManager.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            log.error("Failed to obtain Keycloak service token (registration={})", ServiceClientConfig.REGISTRATION_ID);
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "IAM Auth Failed");
        }
        return client.getAccessToken().getTokenValue();
    }

    public String createUser(String email, String role, String appUserId, String password) {
        String token = getServiceToken();

        List<Map<String, Object>> existing = searchByUserId(appUserId);  // Search by appUserId (username)
        String kcUserId;

        if (!existing.isEmpty()) {
            kcUserId = (String) existing.get(0).get("id");
            log.info("User already exists in Keycloak (idempotency): appUserId={}", appUserId);
        } else {
            // Username is appUserId (stable, immutable), email is an attribute (changeable)
            Map<String, Object> userPayload = (password != null && !password.isBlank())
                    ? Map.of("username", appUserId, "email", email, "enabled", true,
                             "attributes", Map.of("app_user_id", List.of(appUserId)),
                             "credentials", List.of(Map.of("type", "password", "value", password, "temporary", true)))
                    : Map.of("username", appUserId, "email", email, "enabled", true,
                             "attributes", Map.of("app_user_id", List.of(appUserId)));

            try {
                restClient.post()
                        .uri(getAdminUrl() + "/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(userPayload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (HttpClientErrorException.Conflict e) {
                log.warn("User conflict on create (idempotency): appUserId={}", appUserId);
            } catch (RestClientResponseException e) {
                log.error("Failed to create user in Keycloak: {}", e.getResponseBodyAsString());
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "IAM Provisioning Failed");
            }

            List<Map<String, Object>> created = searchByUserId(appUserId);
            if (created.isEmpty()) {
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "User not found after creation");
            }
            kcUserId = (String) created.get(0).get("id");
        }

        assignRole(kcUserId, role, token);
        return kcUserId;
    }

    private void assignRole(String kcUserId, String role, String token) {
        Map<String, Object> roleRepr;
        try {
            roleRepr = restClient.get()
                    .uri(getAdminUrl() + "/roles/" + role)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException e) {
            log.error("Failed to fetch role {} from Keycloak: {}", role, e.getResponseBodyAsString());
            return;
        }
        if (roleRepr != null) {
            try {
                restClient.post()
                        .uri(getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(List.of(roleRepr))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException e) {
                log.error("Failed to assign role to user in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    public void setUserEnabled(String appUserId, boolean enabled) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByUserId(appUserId);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", enabled))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Updated enabled={} in Keycloak for appUserId: {}", enabled, appUserId);
        } catch (RestClientResponseException e) {
            log.error("Failed to update enabled status in Keycloak: {}", e.getResponseBodyAsString());
        }
    }

    public void enableUser(String appUserId)  { setUserEnabled(appUserId, true); }
    public void disableUser(String appUserId) { setUserEnabled(appUserId, false); }

    public void updateUserEmail(String appUserId, String oldEmail, String newEmail) {
        String token = getServiceToken();
        // Try appUserId first, fall back to oldEmail for users created with email as username
        List<Map<String, Object>> users = searchUserWithFallback(appUserId, oldEmail);
        if (users.isEmpty()) {
            log.error("User not found in Keycloak for email update (appUserId={}): {}", appUserId, oldEmail);
            return;
        }
        String kcUserId = (String) users.get(0).get("id");
        try {
            // Only update email (username is immutable, remains appUserId)
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", newEmail))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Updated email → {} in Keycloak for appUserId: {}", newEmail, appUserId);
        } catch (RestClientResponseException e) {
            log.error("Failed to update email in Keycloak: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update email in Keycloak");
        }
    }

    public void setUserRole(String appUserId, String email, String role) {
        String token = getServiceToken();
        // Try appUserId first, fall back to email for old users
        List<Map<String, Object>> users = searchUserWithFallback(appUserId, email);
        if (users.isEmpty()) { log.error("User not found for role update in Keycloak (appUserId={}): {}", appUserId, email); return; }
        String kcUserId = (String) users.get(0).get("id");

        List<Map<String, Object>> currentRoles = restClient.get()
                .uri(getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(LIST_OF_MAPS);

        if (currentRoles != null) {
            List<Map<String, Object>> toRemove = currentRoles.stream()
                    .filter(r -> List.of("ADMIN", "DISPATCHER", "MANAGER")
                            .contains(((String) r.get("name")).toUpperCase()))
                    .toList();
            if (!toRemove.isEmpty()) {
                try {
                    restClient.method(HttpMethod.DELETE)
                            .uri(getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(toRemove)
                            .retrieve()
                            .toBodilessEntity();
                } catch (RestClientResponseException e) {
                    log.error("Failed to remove old roles in Keycloak: {}", e.getResponseBodyAsString());
                }
            }
        }
        assignRole(kcUserId, role, token);
    }

    public void triggerPasswordResetEmail(String appUserId) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByUserId(appUserId);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/execute-actions-email")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of("UPDATE_PASSWORD"))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Triggered UPDATE_PASSWORD email for appUserId: {}", appUserId);
        } catch (RestClientResponseException e) {
            log.error("Failed to trigger password reset: status={}, response={}", e.getStatusCode(), e.getResponseBodyAsString());
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && responseBody.contains("Failed to send execute actions email")) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "SMTP email server is not configured or reachable in Keycloak");
            }
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to trigger password reset email");
        }
    }

    public void forceLogout(String appUserId) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByUserId(appUserId);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.post()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/logout")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Force-logged out user in Keycloak: appUserId={}", appUserId);
        } catch (RestClientResponseException e) {
            log.error("Failed to force logout user in Keycloak: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to force logout user");
        }
    }

    public void deleteUser(String appUserId) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByUserId(appUserId);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.delete()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Deleted user in Keycloak: appUserId={}", appUserId);
        } catch (RestClientResponseException e) {
            log.error("Failed to delete user in Keycloak: {}", e.getResponseBodyAsString());
        }
    }

    public Map<String, Object> getUserDetails(String appUserId) {
        try {
            List<Map<String, Object>> users = searchByUserId(appUserId);
            return users.isEmpty() ? null : users.get(0);
        } catch (Exception e) {
            log.error("Failed to get Keycloak user details for appUserId={}: {}", appUserId, e.getMessage());
            return null;
        }
    }

    public List<String> getUserRoles(String kcUserId) {
        try {
            List<Map<String, Object>> body = restClient.get()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm")
                    .header("Authorization", "Bearer " + getServiceToken())
                    .retrieve()
                    .body(LIST_OF_MAPS);
            if (body != null) {
                return body.stream().map(r -> ((String) r.get("name")).toUpperCase()).toList();
            }
        } catch (Exception e) {
            log.error("Failed to get Keycloak roles for kcUserId={}: {}", kcUserId, e.getMessage());
        }
        return Collections.emptyList();
    }
}
