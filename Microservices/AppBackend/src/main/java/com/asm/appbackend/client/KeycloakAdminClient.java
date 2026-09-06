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
import java.util.HashMap;
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

    @Value("${kc.issuer-uri:http://keycloak:8080/auth/realms/asm}")
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

    public String createUser(String email, String role, String appUserId, String password, String name) {
        return createUser(email, role, appUserId, password, name, null);
    }

    /**
     * Provision (idempotently) a Keycloak user. Now the single provisioning entry point for the whole
     * platform (admins AND drivers — drivers carry a {@code phone} attribute). {@code password} null ⇒
     * the user is created with an UPDATE_PASSWORD required action instead of a server-minted password.
     */
    public String createUser(String email, String role, String appUserId, String password, String name, String phone) {
        String token = getServiceToken();

        List<Map<String, Object>> existing = searchByUserId(appUserId);  // Search by appUserId (username)
        String kcUserId;

        if (!existing.isEmpty()) {
            kcUserId = (String) existing.get(0).get("id");
            log.info("User already exists in Keycloak (idempotency): appUserId={}", appUserId);
            // Keep the display name in sync even on the idempotent path (older users had none).
            applyName(kcUserId, name, token);
            // Heal a missing email. The username is the opaque appUserId, so an account without an
            // email has no credential a human can type — it is locked out however healthy it looks.
            // Restoring it here means a re-provision (reconciler, or an admin re-inviting) is enough
            // to recover, rather than requiring a Keycloak console visit.
            Object currentEmail = existing.get(0).get("email");
            boolean emailMissing = currentEmail == null || currentEmail.toString().isBlank();
            if (emailMissing && email != null && !email.isBlank()) {
                try {
                    patchUser(kcUserId, token, body -> body.put("email", email));
                    log.info("Restored missing email in Keycloak: appUserId={}", appUserId);
                } catch (RestClientResponseException | AppException e) {
                    log.warn("Could not restore missing email (appUserId={}): {}", appUserId, e.getMessage());
                }
            }
        } else {
            // Username is appUserId (stable, immutable), email is an attribute (changeable).
            // firstName/lastName populate the JWT `name` claim → attributable audit/history downstream.
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("app_user_id", List.of(appUserId));
            if (phone != null && !phone.isBlank()) attributes.put("phone", List.of(phone));
            if (role != null && !role.isBlank()) attributes.put("role", List.of(role.toUpperCase()));
            Map<String, Object> userPayload = new HashMap<>();
            userPayload.put("username", appUserId);
            userPayload.put("email", email);
            userPayload.put("enabled", true);
            userPayload.put("attributes", attributes);
            userPayload.putAll(nameFields(name));
            if (password != null && !password.isBlank()) {
                userPayload.put("credentials",
                        List.of(Map.of("type", "password", "value", password, "temporary", true)));
            } else {
                // No password supplied (e.g. reconciler healing a missing user): require the user to
                // set one rather than minting a server-side password in code.
                userPayload.put("requiredActions", List.of("UPDATE_PASSWORD"));
            }

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

    /**
     * Push the display name to Keycloak (firstName/lastName) for an existing user, located by
     * appUserId with email fallback. Best-effort: a failure is logged and left for the reconciler.
     */
    public void updateUserName(String appUserId, String email, String name) {
        if (name == null || name.isBlank()) return;
        String token = getServiceToken();
        List<Map<String, Object>> users = searchUserWithFallback(appUserId, email);
        if (users.isEmpty()) {
            log.warn("User not found in Keycloak for name update (appUserId={})", appUserId);
            return;
        }
        applyName((String) users.get(0).get("id"), name, token);
    }

    private void applyName(String kcUserId, String name, String token) {
        Map<String, Object> body = nameFields(name);
        if (body.isEmpty()) return;
        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Failed to update name in Keycloak (kcUserId={}): {}", kcUserId, e.getResponseBodyAsString());
        }
    }

    /** Split a single display name into Keycloak firstName/lastName. Empty map when name is blank. */
    public static Map<String, Object> nameFields(String fullName) {
        if (fullName == null || fullName.isBlank()) return Map.of();
        String trimmed = fullName.trim();
        int sp = trimmed.indexOf(' ');
        String first = sp < 0 ? trimmed : trimmed.substring(0, sp);
        String last  = sp < 0 ? ""      : trimmed.substring(sp + 1).trim();
        Map<String, Object> m = new HashMap<>();
        m.put("firstName", first);
        m.put("lastName", last);
        return m;
    }

    /**
     * Sets the user's {@code picture} attribute (avatar URL) → surfaced as the OIDC {@code picture} claim.
     * Merges into the existing attribute map so we never drop {@code app_user_id} (the live-lookup key,
     * ADR-026). Requires {@code picture} to be declared in the realm's user-profile config (see asm-realm.json).
     */
    @SuppressWarnings("unchecked")
    public void setUserPicture(String appUserId, String pictureUrl) {
        if (pictureUrl == null || pictureUrl.isBlank()) return;
        String token = getServiceToken();
        List<Map<String, Object>> users = searchUserWithFallback(appUserId, null);
        if (users.isEmpty()) {
            log.warn("User not found in Keycloak for picture update (appUserId={})", appUserId);
            return;
        }
        String kcUserId = (String) users.get(0).get("id");
        try {
            patchUser(kcUserId, token, body -> {
                Map<String, Object> attrs = new HashMap<>();
                if (body.get("attributes") instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) attrs.put(String.valueOf(e.getKey()), e.getValue());
                }
                attrs.put("picture", List.of(pictureUrl));
                body.put("attributes", attrs);
            });
        } catch (RestClientResponseException e) {
            log.error("Failed to set picture in Keycloak (kcUserId={}): {}", kcUserId, e.getResponseBodyAsString());
        } catch (AppException e) {
            log.error("Failed to set picture in Keycloak (kcUserId={}): {}", kcUserId, e.getMessage());
        }
    }

    /**
     * Read-modify-write a Keycloak user, sending the <em>whole</em> representation back.
     *
     * <p>Keycloak's declarative user profile (on by default since 24) treats the body of
     * {@code PUT /users/{id}} as authoritative: a managed attribute the representation omits is
     * taken to have been removed, not left alone. A body of {@code {"attributes": {...}}} therefore
     * does not patch the attributes — it patches them and silently clears {@code email},
     * {@code firstName} and {@code lastName}.
     *
     * <p>That is how five drivers lost the ability to sign in. Their username is the opaque
     * appUserId, so email is the only credential a human can type; uploading a profile photo
     * blanked it and the next login attempt — after a reinstall, once the stored token was gone —
     * answered "invalid username or password" with the account otherwise perfectly healthy:
     * enabled, correct role, password set.
     *
     * <p>Callers mutate the fetched map and this sends all of it back, so a field nobody touched
     * survives by default instead of by remembering to re-send it.
     */
    private void patchUser(String kcUserId, String token, java.util.function.Consumer<Map<String, Object>> mutator) {
        Map<String, Object> full;
        try {
            full = restClient.get()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException e) {
            log.error("Failed to read user before update (kcUserId={}): {}", kcUserId, e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read user from Keycloak");
        }
        if (full == null) {
            throw new AppException(HttpStatus.NOT_FOUND, "User not found in Keycloak");
        }
        Map<String, Object> body = new HashMap<>(full);
        mutator.accept(body);
        restClient.put()
                .uri(getAdminUrl() + "/users/" + kcUserId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
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

    /** Set a permanent password (driver onboarding / admin reset). Synchronous — login works at once. */
    public void resetPassword(String appUserId, String newPassword) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByUserId(appUserId);
        if (users.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "User not found in Keycloak");
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.put()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/reset-password")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", newPassword, "temporary", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Failed to reset password in Keycloak: {}", e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to set credentials in Keycloak");
        }
    }

    public void setUserEnabled(String appUserId, boolean enabled) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByUserId(appUserId);
        if (users.isEmpty()) return;
        String kcUserId = (String) users.get(0).get("id");
        try {
            patchUser(kcUserId, token, body -> body.put("enabled", enabled));
            log.info("Updated enabled={} in Keycloak for appUserId: {}", enabled, appUserId);
        } catch (RestClientResponseException e) {
            log.error("Failed to update enabled status in Keycloak: {}", e.getResponseBodyAsString());
        } catch (AppException e) {
            log.error("Failed to update enabled status in Keycloak: {}", e.getMessage());
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
            // Username is immutable and remains appUserId — only the email moves.
            patchUser(kcUserId, token, body -> body.put("email", newEmail));
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
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && responseBody.contains("Failed to send execute actions email")) {
                // Expected when Keycloak has no SMTP configured (e.g. dev). The caller handles this
                // gracefully, so log at WARN — not ERROR — to avoid noise on every provisioning pass.
                log.warn("Password-reset email not sent (Keycloak SMTP unavailable) for appUserId={}", appUserId);
                throw new AppException(HttpStatus.BAD_GATEWAY, "SMTP email server is not configured or reachable in Keycloak");
            }
            log.error("Failed to trigger password reset: status={}, response={}", e.getStatusCode(), responseBody);
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to trigger password reset email");
        }
    }

    /** Revokes the user's Keycloak sessions. Returns the Keycloak user id (the token {@code sub}),
     *  or {@code null} if the user isn't in Keycloak — callers use it as the session-revocation key. */
    public String forceLogout(String appUserId) {
        String token = getServiceToken();
        List<Map<String, Object>> users = searchByUserId(appUserId);
        if (users.isEmpty()) return null;
        String kcUserId = (String) users.get(0).get("id");
        try {
            restClient.post()
                    .uri(getAdminUrl() + "/users/" + kcUserId + "/logout")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Force-logged out user in Keycloak: appUserId={}", appUserId);
            return kcUserId;
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

    /**
     * Find a Keycloak user by the immutable {@code app_user_id} attribute. Unlike {@link #getUserDetails}
     * (which searches by username), this works regardless of whether the username is the email (seed
     * admins) or the UUID (app-created users / drivers) — the attribute is set on all of them.
     */
    public Map<String, Object> getUserByAppUserId(String appUserId) {
        try {
            List<Map<String, Object>> body = restClient.get()
                    .uri(UriComponentsBuilder.fromHttpUrl(getAdminUrl() + "/users")
                            .queryParam("q", "app_user_id:" + appUserId)
                            .queryParam("exact", "true")
                            .build().toUriString())
                    .header("Authorization", "Bearer " + getServiceToken())
                    .retrieve()
                    .body(LIST_OF_MAPS);
            return (body == null || body.isEmpty()) ? null : body.get(0);
        } catch (Exception e) {
            log.warn("getUserByAppUserId failed for {}: {}", appUserId, e.getMessage());
            return null;
        }
    }

    /** Resolves a Keycloak user id (the token {@code sub}) to its username. For our users the username
     *  is the app-side id (admin_users.id / driver.id), so this maps a logout token's sub back to the
     *  business id used as the session-revocation key (e.g. the driver topic {@code /topic/driver.<id>}). */
    public String getUsernameById(String kcUserId) {
        try {
            Map<String, Object> user = restClient.get()
                    .uri(getAdminUrl() + "/users/" + kcUserId)
                    .header("Authorization", "Bearer " + getServiceToken())
                    .retrieve()
                    .body(MAP_TYPE);
            return user == null ? null : (String) user.get("username");
        } catch (Exception e) {
            log.error("Failed to fetch Keycloak username for kcUserId={}: {}", kcUserId, e.getMessage());
            return null;
        }
    }

    // ── Organizations (multi-tenant) ──────────────────────────────────────────

    /**
     * Creates a Keycloak Organization (= a tenant/company) and returns its id. The org id is the
     * canonical companyId used everywhere downstream (schema {@code company_<id>}). Idempotent-ish:
     * a name/alias conflict surfaces so the caller can decide (we don't silently reuse).
     *
     * @return the created organization's id (parsed from the Location header)
     */
    public String createOrganization(String name, String alias, String domain) {
        String token = getServiceToken();
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("alias", alias);
        payload.put("enabled", true);
        if (domain != null && !domain.isBlank()) {
            payload.put("domains", List.of(Map.of("name", domain)));
        }
        try {
            var response = restClient.post()
                    .uri(getAdminUrl() + "/organizations")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            java.net.URI location = response.getHeaders().getLocation();
            if (location == null) {
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Organization created but no id returned");
            }
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) {
            log.error("Failed to create Keycloak organization '{}': {}", alias, e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Organization creation failed");
        }
    }

    /** Adds a Keycloak user (by its KC user id) as a member of an organization. */
    public void addOrganizationMember(String orgId, String kcUserId) {
        try {
            // KC's add-member endpoint takes the raw user id as the request body (as kcadm does with -b).
            restClient.post()
                    .uri(getAdminUrl() + "/organizations/" + orgId + "/members")
                    .header("Authorization", "Bearer " + getServiceToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(kcUserId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Already a member (reconciler re-provision, or a retried IAM command) is success, not failure —
            // Keycloak answers 409 CONFLICT. Only a genuine error should propagate.
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                log.debug("User {} already a member of organization {} — ok", kcUserId, orgId);
                return;
            }
            log.error("Failed to add member {} to organization {}: {}", kcUserId, orgId, e.getResponseBodyAsString());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Organization membership failed");
        }
    }

    /** Deletes an organization — used to roll back a failed onboarding. Best-effort. */
    public void deleteOrganization(String orgId) {
        try {
            restClient.delete()
                    .uri(getAdminUrl() + "/organizations/" + orgId)
                    .header("Authorization", "Bearer " + getServiceToken())
                    .retrieve()
                    .toBodilessEntity();
            log.info("Deleted Keycloak organization {} (rollback)", orgId);
        } catch (RestClientResponseException e) {
            log.error("Failed to delete organization {} during rollback: {}", orgId, e.getResponseBodyAsString());
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
