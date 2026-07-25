package com.asm.apigateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Runs after Spring Security validates the JWT.
 * Enforces role-based path authorization, then injects X-User-* headers
 * for downstream services (so they don't need to re-parse the token).
 */
@Slf4j
@Component
public class UserContextHeaderFilter implements GlobalFilter, Ordered {

    private static final List<String> ROLE_PRIORITY =
            List.of("ADMIN", "DISPATCHER", "MANAGER", "DRIVER", "CLIENT", "SERVICE");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Strip incoming user context headers using a writable copy to prevent header spoofing
        org.springframework.http.HttpHeaders copiedHeaders = new org.springframework.http.HttpHeaders();
        copiedHeaders.putAll(exchange.getRequest().getHeaders());
        copiedHeaders.remove("X-User-Id");
        copiedHeaders.remove("X-User-Role");
        copiedHeaders.remove("X-User-Name");
        copiedHeaders.remove("X-User-Perms");
        copiedHeaders.remove("X-Company-Id");

        ServerHttpRequest sanitizedRequest = new org.springframework.http.server.reactive.ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return copiedHeaders;
            }
        };
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();

        String path = sanitizedExchange.getRequest().getURI().getPath();

        if (HttpMethod.OPTIONS.equals(sanitizedExchange.getRequest().getMethod()) || isPublic(path)) {
            return chain.filter(sanitizedExchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> {
                    if (!(ctx.getAuthentication() instanceof JwtAuthenticationToken jwtAuth)) {
                        return chain.filter(sanitizedExchange);
                    }

                    Jwt jwt = jwtAuth.getToken();
                    String appUserId = jwt.getClaimAsString("app_user_id");
                    String userId = appUserId != null ? appUserId : jwt.getSubject();
                    java.util.Set<String> roles = extractRoles(jwt);
                    String role = dominantRole(roles);
                    String name = jwt.getClaimAsString("name");

                    if (!isAuthorized(path, roles, sanitizedExchange.getRequest().getMethod())) {
                        log.warn("Access denied on path={} method={} — user roles={}", path,
                                sanitizedExchange.getRequest().getMethod(), roles);
                        return writeError(sanitizedExchange, HttpStatus.FORBIDDEN, "Access denied");
                    }

                    String perms = roles.stream().filter(r -> r.startsWith("perm:"))
                            .collect(java.util.stream.Collectors.joining(","));
                    String companyId = extractCompanyId(jwt);
                    if (companyId == null) {
                        // Fail-closed: no resolvable tenant = no data access. NEVER fall back to a
                        // catch-all company (that silently bleeds data across tenants). A user without
                        // an org_id claim is misconfigured in Keycloak — reject rather than guess.
                        log.warn("Missing org_id/organization claim for user={} on path={} — rejecting", userId, path);
                        return writeError(sanitizedExchange, HttpStatus.FORBIDDEN, "No tenant assigned");
                    }
                    if (AMBIGUOUS_TENANT.equals(companyId)) {
                        // A user in MULTIPLE organizations would get a nondeterministic tenant per
                        // token (claim map iteration order) — silent cross-tenant flapping. The
                        // platform rule is 1 user = 1 company; enforce it here, fail-closed.
                        log.warn("User={} belongs to multiple organizations — ambiguous tenant, rejecting (path={})", userId, path);
                        return writeError(sanitizedExchange, HttpStatus.FORBIDDEN, "Ambiguous tenant assignment");
                    }
                    try {
                        java.util.UUID.fromString(companyId);
                    } catch (IllegalArgumentException e) {
                        // Downstream services 400 on a malformed tenant id; reject at the edge instead.
                        log.warn("Non-UUID organization id '{}' for user={} — rejecting", companyId, userId);
                        return writeError(sanitizedExchange, HttpStatus.FORBIDDEN, "Invalid tenant identifier");
                    }
                    ServerHttpRequest.Builder req = sanitizedExchange.getRequest().mutate()
                            .header("X-User-Id", userId)
                            .header("X-User-Role", role)
                            .header("X-Company-Id", companyId);
                    if (name != null) req.header("X-User-Name", name);
                    if (!perms.isEmpty()) req.header("X-User-Perms", perms);

                    return chain.filter(sanitizedExchange.mutate().request(req.build()).build());
                })
                .switchIfEmpty(chain.filter(sanitizedExchange));
    }

    @Override
    public int getOrder() {
        return -99;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isPublic(String path) {
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/public/")
                || path.startsWith("/api/v1/dev/")
                || path.startsWith("/ws/")
                || path.equals("/ws");
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return java.util.Set.of();
        List<String> roles = (List<String>) realmAccess.get("roles");
        if (roles == null) return java.util.Set.of();
        return roles.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    private String dominantRole(java.util.Set<String> roles) {
        return roles.stream()
                .map(String::toUpperCase)
                .filter(ROLE_PRIORITY::contains)
                .min(Comparator.comparingInt(ROLE_PRIORITY::indexOf))
                .orElse("CLIENT");
    }

    /** Sentinel returned when the token carries more than one organization (see caller). */
    static final String AMBIGUOUS_TENANT = "__ambiguous__";

    @SuppressWarnings("unchecked")
    private String extractCompanyId(Jwt jwt) {
        String orgId = jwt.getClaimAsString("org_id");
        if (orgId != null) return orgId;
        Map<String, Object> orgs = jwt.getClaim("organization");
        if (orgs != null && !orgs.isEmpty()) {
            if (orgs.size() > 1) return AMBIGUOUS_TENANT;
            Object first = orgs.values().iterator().next();
            if (first instanceof Map) {
                Object id = ((Map<?, ?>) first).get("id");
                if (id != null) return id.toString();
            }
        }
        return null;
    }

    /**
     * Path authorization. Delegates to {@link RbacPolicy} — the single, ordered, data-driven table that
     * is the canonical reference (and is exhaustively covered by RbacPolicyTest). No ADMIN superuser
     * bypass: ADMIN passes because its Keycloak composite grants every perm:* the rules require.
     */
    boolean isAuthorized(String path, java.util.Set<String> roles, HttpMethod method) {
        return RbacPolicy.isAuthorized(path, roles, method);
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        // Fail-closed paths can fire while the response is already committed (e.g. a second concurrent
        // reject on the same exchange). setStatusCode/writeWith on a committed response throws
        // UnsupportedOperationException, which surfaces as noise in HttpWebHandlerAdapter — the status
        // is already sent, so just complete quietly instead.
        if (response.isCommitted()) {
            return response.setComplete();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}";
        var buf = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buf));
    }
}
