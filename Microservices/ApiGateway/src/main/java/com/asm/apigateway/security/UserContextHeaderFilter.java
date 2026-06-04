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
                    String role = extractDominantRole(jwt);
                    String name = jwt.getClaimAsString("name");

                    if (!isAuthorized(path, role, sanitizedExchange.getRequest().getMethod())) {
                        log.debug("Access denied for role={} on path={}", role, path);
                        return writeError(sanitizedExchange, HttpStatus.FORBIDDEN,
                                "Access denied for role " + role);
                    }

                    ServerHttpRequest.Builder req = sanitizedExchange.getRequest().mutate()
                            .header("X-User-Id", userId)
                            .header("X-User-Role", role);
                    if (name != null) req.header("X-User-Name", name);

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
    private String extractDominantRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return "CLIENT";
        List<String> roles = (List<String>) realmAccess.get("roles");
        if (roles == null || roles.isEmpty()) return "CLIENT";
        return roles.stream()
                .map(String::toUpperCase)
                .filter(ROLE_PRIORITY::contains)
                .min(Comparator.comparingInt(ROLE_PRIORITY::indexOf))
                .orElse("CLIENT");
    }

    private boolean isAuthorized(String path, String role, HttpMethod method) {
        if ("ADMIN".equals(role)) return true;

        if (path.startsWith("/api/orders/"))   return "CLIENT".equals(role);
        if (path.startsWith("/api/users/"))    return "CLIENT".equals(role);
        if (path.startsWith("/api/driver/"))   return "DRIVER".equals(role);

        if (path.equals("/api/admin/companies/me"))
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);
        if (path.startsWith("/api/admin/companies/"))
            return "ADMIN".equals(role);

        if (path.startsWith("/api/admin/drivers"))
            return HttpMethod.GET.equals(method)
                    && ("ADMIN".equals(role) || "DISPATCHER".equals(role));
        if (path.startsWith("/api/admin/vehicles"))
            return HttpMethod.GET.equals(method)
                    && ("ADMIN".equals(role) || "DISPATCHER".equals(role));

        if (path.startsWith("/api/admin/erp/"))
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);
        if (path.startsWith("/api/admin/reports/settings"))
            return "ADMIN".equals(role);

        if (path.startsWith("/api/admin/stats")
                || path.startsWith("/api/admin/reports/")
                || path.startsWith("/api/admin/ops/"))
            return "ADMIN".equals(role) || "DISPATCHER".equals(role) || "MANAGER".equals(role);

        if ("MANAGER".equals(role) && HttpMethod.GET.equals(method))
            return path.startsWith("/api/admin/routes") || path.startsWith("/api/admin/deliveries");

        if (path.startsWith("/api/admin/"))
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);

        if (path.startsWith("/api/deliveries/"))
            return "DRIVER".equals(role) || "DISPATCHER".equals(role) || "ADMIN".equals(role);

        if (path.startsWith("/api/v1/"))
            return "ADMIN".equals(role) || "DISPATCHER".equals(role) || "MANAGER".equals(role);

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
