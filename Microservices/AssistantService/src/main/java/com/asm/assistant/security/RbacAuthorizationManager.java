package com.asm.assistant.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Single authorization entry point for the whole request surface: delegates every decision to the
 * canonical {@link RbacPolicy} (the shared rbac-policy.json). Replaces the per-path {@code hasAuthority}
 * matchers so this service never re-encodes the authorization table — it just evaluates the one policy.
 */
@Component
public class RbacAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        // "Authenticated" in the policy means: someone signed in. The anonymous identity does not
        // count, however technically authenticated Spring considers it.
        //
        // Spring never leaves a request without one: with no token it installs an anonymous
        // principal, and that principal answers true to isAuthenticated(). A rule written
        // {"authenticated": true} — the profile, the notifications — was therefore satisfied by a
        // caller holding nothing at all, for anyone reaching a service directly instead of through
        // the gateway.
        //
        // Excluded by type rather than by requiring a bearer token: JwtAuthConverter hands us a
        // UsernamePasswordAuthenticationToken carrying the UserPrincipal, so demanding an OAuth2
        // token type here would refuse every real user.
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        // Normalize to the gateway's raw-realm-roles form: strip Spring's ROLE_ prefix, keep perm:* as-is.
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .collect(Collectors.toSet());
        HttpMethod method = HttpMethod.valueOf(context.getRequest().getMethod());
        boolean ok = RbacPolicy.isAuthorized(context.getRequest().getRequestURI(), roles, method);
        return new AuthorizationDecision(ok);
    }
}
