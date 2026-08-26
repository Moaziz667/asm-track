package com.asm.assistant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A few routes are guarded by {@code {"authenticated": true}} alone — the caller's own profile, his
 * notifications. The word is ambiguous, and the ambiguity once cost us: Spring gives a request with
 * no token an anonymous identity that answers <em>true</em> to {@code isAuthenticated()}, so those
 * routes were open to a caller holding nothing, for anyone reaching a service directly instead of
 * through the gateway.
 *
 * <p>Two things make this test worth its lines:
 *
 * <p>It reads the policy rather than a hard-coded list of paths, so a rule added later with the
 * same requirement is covered the day it ships.
 *
 * <p>And it builds its principals with the real {@link JwtAuthConverter} rather than a token typed
 * by hand. That is not a detail: the converter returns a {@code UsernamePasswordAuthenticationToken}
 * carrying the {@code UserPrincipal}, not a {@code JwtAuthenticationToken}. A hand-made fixture once
 * hid a guard that demanded the OAuth2 token type and therefore refused every real user — green
 * tests, and the whole platform answering 403.
 */
class AnonymousIsNotAuthenticatedTest {

    private final RbacAuthorizationManager manager = new RbacAuthorizationManager();
    private final JwtAuthConverter converter = new JwtAuthConverter();

    /** Every path in the canonical policy whose rule is satisfied by the mere fact of being authenticated. */
    @SuppressWarnings("unchecked")
    private static List<String> authenticatedOnlyPaths() throws Exception {
        try (var in = new ClassPathResource("rbac-policy.json").getInputStream()) {
            Map<String, Object> doc = new ObjectMapper().readValue(in, Map.class);
            return ((List<Map<String, Object>>) doc.get("rules")).stream()
                    .filter(r -> Boolean.TRUE.equals(((Map<String, Object>) r.get("require")).get("authenticated")))
                    .map(r -> r.get("pathExact") != null
                            ? (String) r.get("pathExact")
                            : (String) r.get("pathPrefix"))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
    }

    private static RequestAuthorizationContext get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return new RequestAuthorizationContext((HttpServletRequest) request);
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    /** A signed-in user, built exactly as the filter chain builds one. */
    private Authentication signedIn(String realmRole) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("1c9e2f4a-0000-0000-0000-000000000001")
                .claim("realm_access", Map.of("roles", List.of(realmRole)))
                .claim("org_id", "aaaaaaaa-0000-0000-0000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return converter.convert(jwt);
    }

    @Test
    void thePolicyReallyHasAuthenticatedOnlyRoutes() throws Exception {
        assertThat(authenticatedOnlyPaths())
                .as("if this ever empties, the test below stops proving anything")
                .isNotEmpty();
    }

    @Test
    void anonymousIsRefusedEverywhereAuthenticationAloneWouldSuffice() throws Exception {
        for (String path : authenticatedOnlyPaths()) {
            assertThat(manager.check(AnonymousIsNotAuthenticatedTest::anonymous, get(path)).isGranted())
                    .as("a caller with no token must not reach %s", path)
                    .isFalse();
        }
    }

    @Test
    void aSignedInUserIsAccepted() throws Exception {
        Authentication dispatcher = signedIn("DISPATCHER");
        for (String path : authenticatedOnlyPaths()) {
            assertThat(manager.check(() -> dispatcher, get(path)).isGranted())
                    .as("a signed-in operator must still reach %s", path)
                    .isTrue();
        }
    }

    /**
     * The counterpart: holding a token is not the same as being allowed. A client signs in with a
     * perfectly valid token and still has no business in an assistant that reads across the tenant.
     */
    @Test
    void aValidTokenIsNotEnoughForAGuardedRoute() {
        Authentication client = signedIn("CLIENT");
        Authentication dispatcher = signedIn("DISPATCHER");
        assertThat(manager.check(() -> client, get("/api/assistant/query")).isGranted())
                .as("the assistant answers to the operator roles, not to any signed-in user")
                .isFalse();
        assertThat(manager.check(() -> dispatcher, get("/api/assistant/query")).isGranted())
                .as("a dispatcher does reach it")
                .isTrue();
    }

    /**
     * Guards the assumption the whole file rests on. If the converter ever starts returning another
     * token type, the tests above would still pass while the running service refused everyone.
     */
    @Test
    void theConverterReturnsTheTokenTypeTheGuardExpects() {
        assertThat(signedIn("DISPATCHER"))
                .isInstanceOf(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class);
        assertThat(signedIn("DISPATCHER").isAuthenticated()).isTrue();
    }
}
