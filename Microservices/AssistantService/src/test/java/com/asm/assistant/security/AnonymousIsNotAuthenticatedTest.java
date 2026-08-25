package com.asm.assistant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
 * <p>This test reads the policy rather than a hard-coded list of paths: a rule added later with the
 * same {@code authenticated} requirement is covered the day it ships, without anyone remembering
 * this file exists. That is the part that lasts — the fix in
 * {@link RbacAuthorizationManager} only holds until someone rewrites the line.
 */
class AnonymousIsNotAuthenticatedTest {

    private final RbacAuthorizationManager manager = new RbacAuthorizationManager();

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

    private static Authentication bearer() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "1c9e2f4a-0000-0000-0000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER")));
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
            assertThat(manager.check(AnonymousIsNotAuthenticatedTest::anonymousStatic, get(path)).isGranted())
                    .as("a caller with no token must not reach %s", path)
                    .isFalse();
        }
    }

    @Test
    void aRealBearerTokenIsAccepted() throws Exception {
        for (String path : authenticatedOnlyPaths()) {
            assertThat(manager.check(AnonymousIsNotAuthenticatedTest::bearerStatic, get(path)).isGranted())
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
        assertThat(manager.check(AnonymousIsNotAuthenticatedTest::clientStatic, get("/api/assistant/query")).isGranted())
                .as("the assistant answers to the operator roles, not to any bearer")
                .isFalse();
        assertThat(manager.check(AnonymousIsNotAuthenticatedTest::bearerStatic, get("/api/assistant/query")).isGranted())
                .as("a dispatcher does reach it")
                .isTrue();
    }

    private static Authentication anonymousStatic() {
        return anonymous();
    }

    private static Authentication bearerStatic() {
        return bearer();
    }

    private static Authentication clientStatic() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "1c9e2f4a-0000-0000-0000-000000000002")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }
}
