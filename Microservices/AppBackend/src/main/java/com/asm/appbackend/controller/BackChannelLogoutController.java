package com.asm.appbackend.controller;

import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.messaging.AuditEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OIDC Back-Channel Logout receiver (OpenID Connect Back-Channel Logout 1.0).
 *
 * <p>Keycloak POSTs a signed {@code logout_token} here whenever an {@code admin-web} OR
 * {@code driver-app} session ends — from ANY source (account console "sign out", KC admin console,
 * our own force-logout). We validate it and re-use the existing session-revocation pipeline so the
 * client logs out instantly instead of waiting for the access token to expire (token-expiry is the
 * backstop). The token's client (azp/aud) selects the target:
 * <ul>
 *   <li>{@code admin-web} → publish the {@code sub} → {@code /topic/admin.security} (SPA matches sub).</li>
 *   <li>{@code driver-app} → resolve sub → driver id (KC username) → {@code /topic/driver.<id>}.</li>
 * </ul>
 *
 * <p>Public endpoint (matched by the {@code /api/auth/**} permitAll rule): authenticity comes from
 * the JWT signature (realm JWKS), not a bearer token.
 */
@Slf4j
@RestController
@Tag(name = "Auth · Back-Channel Logout", description = "OIDC Back-Channel Logout 1.0 receiver. Keycloak (not a "
        + "user) POSTs a signed logout_token here when a session ends, so the client logs out instantly. Public "
        + "endpoint — authenticity comes from the token's realm signature, not a bearer token.")
public class BackChannelLogoutController {

    /** Marker the logout token must carry in its {@code events} claim. */
    private static final String BACKCHANNEL_LOGOUT_EVENT =
            "http://schemas.openid.net/event/backchannel-logout";

    /** Dedicated decoder (accepts {@code typ: logout+jwt}) — explicitly qualified because the default
     *  access-token decoder is {@code @Primary} and would otherwise win injection. */
    private static final String DRIVER_CLIENT = "driver-app";

    private final JwtDecoder logoutTokenJwtDecoder;
    private final AuditEventPublisher auditEventPublisher;
    private final KeycloakAdminClient keycloakAdminClient;

    public BackChannelLogoutController(
            @Qualifier("logoutTokenJwtDecoder") JwtDecoder logoutTokenJwtDecoder,
            AuditEventPublisher auditEventPublisher,
            KeycloakAdminClient keycloakAdminClient) {
        this.logoutTokenJwtDecoder = logoutTokenJwtDecoder;
        this.auditEventPublisher = auditEventPublisher;
        this.keycloakAdminClient = keycloakAdminClient;
    }

    @PostMapping(value = "/api/auth/backchannel-logout",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Receive an OIDC back-channel logout",
            description = "Validates the signed logout_token (form-encoded) and revokes the matching session so "
                    + "the admin-web or driver-app client logs out immediately. Always returns 200 to Keycloak, "
                    + "even on a token it can't act on, per the spec.")
    @ApiResponse(responseCode = "200", description = "Logout processed (or safely ignored)")
    public ResponseEntity<Void> backchannelLogout(@RequestParam("logout_token") String logoutToken) {
        final Jwt jwt;
        try {
            // Verifies signature (realm JWKS) + issuer; accepts the logout+jwt typ header.
            jwt = logoutTokenJwtDecoder.decode(logoutToken);
        } catch (JwtException e) {
            int len = logoutToken == null ? -1 : logoutToken.length();
            String head = (logoutToken == null || len == 0) ? "<empty>"
                    : logoutToken.substring(0, Math.min(40, len));
            int dots = logoutToken == null ? 0 : (int) logoutToken.chars().filter(c -> c == '.').count();
            log.warn("Back-channel logout: invalid logout_token (len={}, dots={}, head='{}'): {}",
                    len, dots, head, e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // Spec: a logout token MUST carry the backchannel-logout event and MUST NOT carry a nonce.
        // This stops a regular ID/access token from being replayed to force-logout users.
        Object events = jwt.getClaim("events");
        if (events == null || !events.toString().contains(BACKCHANNEL_LOGOUT_EVENT)) {
            log.warn("Back-channel logout: token missing the backchannel-logout event claim");
            return ResponseEntity.badRequest().build();
        }
        if (jwt.getClaimAsString("nonce") != null) {
            log.warn("Back-channel logout: token unexpectedly carries a nonce");
            return ResponseEntity.badRequest().build();
        }

        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            return ResponseEntity.ok().build();
        }

        if (DRIVER_CLIENT.equals(clientOf(jwt))) {
            // Drivers listen on /topic/driver.<driverId>, keyed by the business id (= KC username).
            String driverId = keycloakAdminClient.getUsernameById(sub);
            auditEventPublisher.publishDriverSessionRevoked(driverId);
            log.info("Back-channel logout processed for driver sub={} -> driverId={}", sub, driverId);
        } else {
            // admin-web (default): SPA matches token.profile.sub on /topic/admin.security.
            auditEventPublisher.publishSessionRevoked(sub);
            log.info("Back-channel logout processed for admin sub={}", sub);
        }
        return ResponseEntity.ok().build();
    }

    /** The client the logout token was issued for: {@code azp} if present, else the first audience. */
    private static String clientOf(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        if (azp != null && !azp.isBlank()) return azp;
        List<String> aud = jwt.getAudience();
        return (aud == null || aud.isEmpty()) ? null : aud.get(0);
    }
}
