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

    private String userSearchUrl(String email) {
        return UriComponentsBuilder.fromHttpUrl(getAdminUrl() + "/users")
                .queryParam("username", email)
                .queryParam("exact", "true")
                .build()
                .toUriString();
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

    private List<Map<String, Object>> searchByEmail(String email) {
        List<Map<String, Object>> body = restClient.get()
                .uri(userSearchUrl(email))
                .header("Authorization", "Bearer " + getServiceToken())
                .retrieve()
                .body(LIST_OF_MAPS);
        return body != null ? body : Collections.emptyList();
    }

    public String createUser(String email, String role, String appUserId, String password) {
        String token = getServiceToken();

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
                restClient.post()
                        .uri(getAdminUrl() + "/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(userPayload)
                        .retrieve()
                        .toBodilessEntity();
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
        } catch (HttpClientErrorException e) {
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
            } catch (HttpClientErrorException e) {
                log.error("Failed to assign role to user in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    public void setUserEnabled(String email, boolean enabled) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByEmail(email);
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
            log.info("Updated enabled={} in Keycloak for user: {}", enabled, email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to update enabled status in Keycloak: {}", e.getResponseBodyAsString());
        }
    }

    public void enableUser(String email)  { setUserEnabled(email, true); }
    public void disableUser(String email) { setUserEnabled(email, false); }

    public void updateUserEmail(String oldEmail, String newEmail) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByEmail(oldEmail);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", newEmail, "username", newEmail))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Updated email {} → {} in Keycloak", oldEmail, newEmail);
        } catch (HttpClientErrorException e) {
            log.error("Failed to update email in Keycloak: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update email in Keycloak");
        }
    }

    public void setUserRole(String email, String role) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) { log.error("User not found for role update in Keycloak: {}", email); return; }
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
                } catch (HttpClientErrorException e) {
                    log.error("Failed to remove old roles in Keycloak: {}", e.getResponseBodyAsString());
                }
            }
        }
        assignRole(kcUserId, role, token);
    }

    public void triggerPasswordResetEmail(String email) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByEmail(email);
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
            log.info("Triggered UPDATE_PASSWORD email for: {}", email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to trigger password reset: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to trigger password reset email");
        }
    }

    public void forceLogout(String email) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.post()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/logout")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Force-logged out user in Keycloak: {}", email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to force logout user in Keycloak: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to force logout user");
        }
    }

    public void deleteUser(String email) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByEmail(email);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.delete()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
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
