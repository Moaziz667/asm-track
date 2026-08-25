package com.asm.assistant.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
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
        // "Authenticated" in the policy means: bearer of a validated token. Nothing else qualifies.
        //
        // Stated as a whitelist on purpose. Spring never leaves a request without an identity: with
        // no token it installs an anonymous one, which answers true to isAuthenticated(). A rule
        // written {"authenticated": true} — the profile, the notifications — was therefore satisfied
        // by a caller holding nothing at all, for anyone reaching a service directly instead of
        // through the gateway. Excluding anonymous by name would fix that one case; requiring the
        // token type fixes the class, including whatever Spring may install later (remember-me,
        // pre-authentication, a test principal) without anyone having to think about it again.
        if (!(auth instanceof AbstractOAuth2TokenAuthenticationToken<?>) || !auth.isAuthenticated()) {
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
