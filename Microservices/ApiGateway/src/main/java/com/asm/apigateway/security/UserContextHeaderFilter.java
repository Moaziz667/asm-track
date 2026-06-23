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
                        log.debug("Access denied for roles={} on path={}", roles, path);
                        return writeError(sanitizedExchange, HttpStatus.FORBIDDEN, "Access denied");
                    }

                    String perms = roles.stream().filter(r -> r.startsWith("perm:"))
                            .collect(java.util.stream.Collectors.joining(","));
                    ServerHttpRequest.Builder req = sanitizedExchange.getRequest().mutate()
                            .header("X-User-Id", userId)
                            .header("X-User-Role", role);
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
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/public/")
                || path.startsWith("/api/dev/")
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

    private static boolean hasRole(java.util.Set<String> roles, String role) {
        return roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    /**
     * Permission-based path authorization (RBAC). Permissions ride in the JWT as composite-role
     * members (perm:*). This table is a behavior-preserving translation of the previous role table:
     * ADMIN short-circuits (superuser); client/driver-facing paths stay role-scoped; the admin surface
     * is authorized on perm:* so access is managed in Keycloak, not hardcoded here.
     */
    boolean isAuthorized(String path, java.util.Set<String> roles, HttpMethod method) {
        if (hasRole(roles, "ADMIN")) return true; // superuser safety net during RBAC rollout
        boolean isGet = HttpMethod.GET.equals(method);

        // Client/driver-facing paths — role-scoped, not part of the admin permission model.
        if (path.startsWith("/api/orders/")) return hasRole(roles, "CLIENT");
        if (path.startsWith("/api/users/"))  return hasRole(roles, "CLIENT");
        if (path.startsWith("/api/driver/")) return hasRole(roles, "DRIVER");

        // Admin surface — permission-based (order matters: most specific first).
        if (path.equals("/api/admin/companies/me"))
            return roles.contains("perm:company:manage") || roles.contains("perm:dispatch:operate");
        if (path.startsWith("/api/admin/companies/"))
            return roles.contains("perm:company:manage");
        if (path.startsWith("/api/admin/users"))
            return roles.contains("perm:user:manage");
        if (path.startsWith("/api/admin/drivers"))
            return isGet ? roles.contains("perm:driver:view") : roles.contains("perm:driver:manage");
        if (path.startsWith("/api/admin/vehicles"))
            return isGet && roles.contains("perm:driver:view");
        if (path.startsWith("/api/admin/erp/"))
            return roles.contains("perm:erp:sync");
        if (path.startsWith("/api/admin/reports/settings"))
            return roles.contains("perm:settings:manage");
        if (path.startsWith("/api/admin/stats")
                || path.startsWith("/api/admin/reports/")
                || path.startsWith("/api/admin/ops/"))
            return roles.contains("perm:report:view");
        if (isGet && path.startsWith("/api/admin/routes"))
            return roles.contains("perm:route:view");
        if (isGet && path.startsWith("/api/admin/deliveries"))
            return roles.contains("perm:delivery:view");
        if (path.startsWith("/api/admin/"))
            return roles.contains("perm:dispatch:operate");

        if (path.startsWith("/api/deliveries/"))
            return hasRole(roles, "DRIVER") || roles.contains("perm:delivery:view");
        if (path.startsWith("/api/v1/"))
            return roles.contains("perm:route:view");

        return false;
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}";
        var buf = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buf));
    }
}
