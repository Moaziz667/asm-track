package com.asm.appbackend.client;

import com.asm.appbackend.exception.AppException;
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

    @Value("${auth.client.id:app-backend}")
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
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "IAM Auth Failed");
        }
    }

    public String createUser(String email, String role, String appUserId, String password) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Idempotency check: look up user by email/username first
        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + email + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        String kcUserId = null;
        if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
            Map<String, Object> existingUser = (Map<String, Object>) searchResp.getBody().get(0);
            kcUserId = (String) existingUser.get("id");
            log.info("User already exists in Keycloak (idempotency check): email={}", email);
        } else {
            // 1. Create user
            Map<String, Object> userPayload;
            if (password != null && !password.isBlank()) {
                userPayload = Map.of(
                        "username", email,
                        "email", email,
                        "enabled", true,
                        "attributes", Map.of("app_user_id", List.of(appUserId)),
                        "credentials", List.of(Map.of(
                                "type", "password",
                                "value", password,
                                "temporary", true
                        ))
                );
            } else {
                userPayload = Map.of(
                        "username", email,
                        "email", email,
                        "enabled", true,
                        "attributes", Map.of("app_user_id", List.of(appUserId))
                );
            }

            try {
                restTemplate.postForEntity(getAdminUrl() + "/users", new HttpEntity<>(userPayload, headers), String.class);
            } catch (HttpClientErrorException.Conflict e) {
                log.warn("User already exists in Keycloak on post (conflict): {}", email);
            } catch (HttpClientErrorException e) {
                log.error("Failed to create user in Keycloak: {}", e.getResponseBodyAsString());
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "IAM Provisioning Failed");
            }

            // 2. Get the generated user ID
            searchResp = restTemplate.exchange(
                    getAdminUrl() + "/users?username=" + email + "&exact=true",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    List.class);

            if (searchResp.getBody() == null || searchResp.getBody().isEmpty()) {
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "User not found after creation");
            }
            Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
            kcUserId = (String) user.get("id");
        }

        // 3. Assign role
        assignRole(kcUserId, role, headers);

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

    public void setUserEnabled(String email, boolean enabled) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + email + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
            Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
            String kcUserId = (String) user.get("id");

            Map<String, Object> updatePayload = Map.of("enabled", enabled);
            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId,
                        HttpMethod.PUT,
                        new HttpEntity<>(updatePayload, headers),
                        String.class);
                log.info("Successfully updated enabled={} in Keycloak for user: {}", enabled, email);
            } catch (HttpClientErrorException e) {
                log.error("Failed to update user enabled status in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    public void enableUser(String email) {
        setUserEnabled(email, true);
    }

    public void disableUser(String email) {
        setUserEnabled(email, false);
    }

    public void updateUserEmail(String oldEmail, String newEmail) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + oldEmail + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
            Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
            String kcUserId = (String) user.get("id");

            Map<String, Object> updatePayload = Map.of(
                    "email", newEmail,
                    "username", newEmail
            );
            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId,
                        HttpMethod.PUT,
                        new HttpEntity<>(updatePayload, headers),
                        Void.class);
                log.info("Successfully updated user email from {} to {} in Keycloak", oldEmail, newEmail);
            } catch (HttpClientErrorException e) {
                log.error("Failed to update user email in Keycloak: {}", e.getResponseBodyAsString());
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update email in Keycloak");
            }
        }
    }

    public void setUserRole(String email, String role) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + email + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() == null || searchResp.getBody().isEmpty()) {
            log.error("User not found to update role in Keycloak: {}", email);
            return;
        }
        Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
        String kcUserId = (String) user.get("id");

        // 1. Fetch current mappings
        ResponseEntity<List> mappingsResp = restTemplate.exchange(
                getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        List<Map<String, Object>> currentRoles = mappingsResp.getBody();
        if (currentRoles != null) {
            // Filter administrative roles that we manage
            List<Map<String, Object>> rolesToRemove = currentRoles.stream()
                    .filter(r -> List.of("ADMIN", "DISPATCHER", "MANAGER").contains(((String) r.get("name")).toUpperCase()))
                    .toList();

            if (!rolesToRemove.isEmpty()) {
                try {
                    restTemplate.exchange(
                            getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                            HttpMethod.DELETE,
                            new HttpEntity<>(rolesToRemove, headers),
                            Void.class);
                } catch (HttpClientErrorException e) {
                    log.error("Failed to remove old roles from user in Keycloak: {}", e.getResponseBodyAsString());
                }
            }
        }

        // 2. Assign the new role
        assignRole(kcUserId, role, headers);
    }

    public void triggerPasswordResetEmail(String email) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + email + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
            Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
            String kcUserId = (String) user.get("id");

            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId + "/execute-actions-email",
                        HttpMethod.PUT,
                        new HttpEntity<>(List.of("UPDATE_PASSWORD"), headers),
                        String.class);
                log.info("Triggered execute-actions-email (UPDATE_PASSWORD) in Keycloak for user: {}", email);
            } catch (HttpClientErrorException e) {
                log.error("Failed to trigger password reset email in Keycloak: {}", e.getResponseBodyAsString());
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to trigger password reset email");
            }
        }
    }

    public void forceLogout(String email) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + email + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
            Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
            String kcUserId = (String) user.get("id");

            try {
                restTemplate.postForEntity(
                        getAdminUrl() + "/users/" + kcUserId + "/logout",
                        new HttpEntity<>(null, headers),
                        Void.class);
                log.info("Triggered force logout in Keycloak for user: {}", email);
            } catch (HttpClientErrorException e) {
                log.error("Failed to force logout user in Keycloak: {}", e.getResponseBodyAsString());
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to force logout user");
            }
        }
    }

    public void deleteUser(String email) {
        String token = getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<List> searchResp = restTemplate.exchange(
                getAdminUrl() + "/users?username=" + email + "&exact=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);

        if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
            Map<String, Object> user = (Map<String, Object>) searchResp.getBody().get(0);
            String kcUserId = (String) user.get("id");
            try {
                restTemplate.exchange(
                        getAdminUrl() + "/users/" + kcUserId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(headers),
                        Void.class);
                log.info("Successfully deleted user in Keycloak: {}", email);
            } catch (HttpClientErrorException e) {
                log.error("Failed to delete user in Keycloak: {}", e.getResponseBodyAsString());
            }
        }
    }

    public Map<String, Object> getUserDetails(String email) {
        try {
            String token = getToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);

            ResponseEntity<List> searchResp = restTemplate.exchange(
                    getAdminUrl() + "/users?username=" + email + "&exact=true",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    List.class);

            if (searchResp.getBody() != null && !searchResp.getBody().isEmpty()) {
                return (Map<String, Object>) searchResp.getBody().get(0);
            }
        } catch (Exception e) {
            log.error("Failed to get Keycloak user details for email={}: {}", email, e.getMessage());
        }
        return null;
    }

    public List<String> getUserRoles(String kcUserId) {
        try {
            String token = getToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);

            ResponseEntity<List> mappingsResp = restTemplate.exchange(
                    getAdminUrl() + "/users/" + kcUserId + "/role-mappings/realm",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    List.class);

            List<Map<String, Object>> body = mappingsResp.getBody();
            if (body != null) {
                return body.stream()
                        .map(r -> ((String) r.get("name")).toUpperCase())
                        .toList();
            }
        } catch (Exception e) {
            log.error("Failed to get Keycloak roles for user ID={}: {}", kcUserId, e.getMessage());
        }
        return java.util.Collections.emptyList();
    }
}
