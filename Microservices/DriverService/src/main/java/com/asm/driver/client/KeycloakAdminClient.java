package com.asm.driver.client;

import com.asm.driver.config.ServiceClientConfig;
import com.asm.driver.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Thin client over the Keycloak Admin REST API for driver provisioning. The {@code client_credentials}
 * service token is acquired/cached/refreshed by Spring Security's {@link OAuth2AuthorizedClientManager}
 * (shared {@code asm-svc} registration) — replacing the previous fetch-on-every-call anti-pattern.
 * HTTP uses {@link RestClient}, which raises the same {@link HttpClientErrorException} hierarchy.
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

    private String getToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(ServiceClientConfig.REGISTRATION_ID)
                .principal(ServiceClientConfig.REGISTRATION_ID)
                .build();
        OAuth2AuthorizedClient client = authorizedClientManager.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            log.error("Failed to obtain Keycloak service token (registration={})", ServiceClientConfig.REGISTRATION_ID);
            throw AppException.internal("IAM Auth Failed");
        }
        return client.getAccessToken().getTokenValue();
    }

    public String createDriver(String appUserId, String email, String phone) {
        String token = getToken();

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
            restClient.post()
                    .uri(getAdminUrl() + "/users")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(userPayload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Driver already exists in Keycloak: {}", appUserId);
        } catch (HttpClientErrorException e) {
            log.error("Failed to create driver in Keycloak: {}", e.getResponseBodyAsString());
            throw AppException.internal("IAM Provisioning Failed");
        }

        // 2. Get generated ID
        String kcUserId = getUserIdByUsername(appUserId, token);
        if (kcUserId == null) {
            throw AppException.internal("User not found after creation");
        }

        // 3. Assign DRIVER role
        assignRole(kcUserId, "DRIVER", token);

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

    public void triggerInviteEmail(String appUserId) {
        String token = getToken();
        String kcUserId = getUserIdByUsername(appUserId, token);
        if (kcUserId != null) {
            triggerInvite(kcUserId, token);
        }
    }

    public void deleteDriver(String appUserId) {
        String token = getToken();
        String kcUserId = getUserIdByUsername(appUserId, token);
        if (kcUserId != null) {
            try {
                restClient.delete()
                        .uri(getAdminUrl() + "/users/" + kcUserId)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Successfully deleted user in Keycloak: {}", appUserId);
            } catch (HttpClientErrorException e) {
                log.error("Failed to delete user in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    private String getUserIdByUsername(String username, String token) {
        List<Map<String, Object>> body = restClient.get()
                .uri(getAdminUrl() + "/users?username=" + username + "&exact=true")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(LIST_OF_MAPS);
        if (body != null && !body.isEmpty()) {
            return (String) body.get(0).get("id");
        }
        return null;
    }

    private void triggerInvite(String kcUserId, String token) {
        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/execute-actions-email")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of("UPDATE_PASSWORD"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.error("Failed to trigger invite email: {}", e.getResponseBodyAsString());
        }
    }

    public void setUserEnabled(String appUserId, boolean enabled) {
        String token = getToken();
        String kcUserId = getUserIdByUsername(appUserId, token);
        if (kcUserId != null) {
            try {
                restClient.put()
                        .uri(getAdminUrl() + "/users/" + kcUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("enabled", enabled))
                        .retrieve()
                        .toBodilessEntity();
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
        String kcUserId = getUserIdByUsername(appUserId, token);
        if (kcUserId == null) {
            throw AppException.notFound("Driver not found in Keycloak");
        }

        Map<String, Object> credential = Map.of(
                "type", "password",
                "value", newPassword,
                "temporary", false
        );

        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/reset-password")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(credential)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Successfully updated password in Keycloak for driver: {}", appUserId);
        } catch (HttpClientErrorException e) {
            log.error("Failed to reset password in Keycloak: {}", e.getResponseBodyAsString());
            throw AppException.internal("Failed to set credentials in Keycloak");
        }
    }

    public void updateDriverEmail(String appUserId, String email) {
        String token = getToken();
        String kcUserId = getUserIdByUsername(appUserId, token);
        if (kcUserId == null) {
            log.warn("User not found in Keycloak for email update: {}", appUserId);
            return;
        }

        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.error("Failed to update user email in Keycloak: {}", e.getResponseBodyAsString());
            throw AppException.internal("Failed to update email in Keycloak");
        }
    }

    public void forceLogout(String appUserId) {
        String token = getToken();
        String kcUserId = getUserIdByUsername(appUserId, token);
        if (kcUserId != null) {
            try {
                restClient.post()
                        .uri(getAdminUrl() + "/users/" + kcUserId + "/logout")
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .toBodilessEntity();
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
            List<Map<String, Object>> body = restClient.get()
                    .uri(getAdminUrl() + "/users?username=" + appUserId + "&exact=true")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(LIST_OF_MAPS);
            if (body != null && !body.isEmpty()) {
                return body.get(0);
            }
        } catch (Exception e) {
            log.error("Failed to get Keycloak user details for driver appUserId={}: {}", appUserId, e.getMessage());
        }
        return null;
    }
}
