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
        // Anonymous counts as unauthenticated. An AnonymousAuthenticationToken answers true to
        // isAuthenticated(), so without this an caller with no token at all would satisfy every
        // rule written as {"authenticated": true} — /api/v1/admin/me, /api/v1/admin/notifications
        // and the assistant among them. The gateway rejects such a call upstream; this is what
        // holds when a service is reached directly, which is the whole point of bundling the policy.
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
