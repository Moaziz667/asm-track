package com.asm.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        if (path.startsWith("/internal/")) {
            return writeError(exchange.getResponse(), HttpStatus.FORBIDDEN, "Direct access to internal endpoints is blocked");
        }

        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        // Fall back to HttpOnly cookie — admin web app uses cookie-based auth
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            org.springframework.http.HttpCookie accessCookie =
                    exchange.getRequest().getCookies().getFirst("access_token");
            if (accessCookie != null && !accessCookie.getValue().isBlank()) {
                authHeader = "Bearer " + accessCookie.getValue();
                log.debug("Using access_token cookie as Bearer token");
            }
        }

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Missing or invalid Authorization header");
            return writeError(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtService.parseToken(token);
            log.debug("Gateway parsed JWT claims: {}", claims);

            String userId = claims.getSubject();
            String role = claims.get("role", String.class);

            if (userId == null || userId.isBlank() || role == null || role.isBlank()) {
                log.debug("JWT has missing userId or role (userId={} role={})", userId, role);
                return writeError(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "Invalid token payload");
            }

            String normalizedRole = role.toUpperCase();
            if (!isAuthorized(path, normalizedRole)) {
                log.debug("Access denied for role {} on path {}", normalizedRole, path);
                return writeError(exchange.getResponse(), HttpStatus.FORBIDDEN, "Access denied for role " + normalizedRole);
            }

            ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate()
                    .header("Authorization", authHeader)  // ensure downstream always sees Bearer token
                    .header("X-User-Id", userId)
                    .header("X-User-Role", normalizedRole);

            if (claims.get("name", String.class) != null) {
                reqBuilder.header("X-User-Name", claims.get("name", String.class));
            }
            if (claims.get("odooPartnerId", Integer.class) != null) {
                reqBuilder.header("X-Odoo-Partner-Id", String.valueOf(claims.get("odooPartnerId", Integer.class)));
            }
            if (claims.get("companyId", String.class) != null) {
                reqBuilder.header("X-Company-Id", claims.get("companyId", String.class));
            }
            ServerHttpRequest mutatedRequest = reqBuilder.build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT parse/validation failed: {}", ex.getMessage());
            return writeError(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }

    private boolean isPublic(String path) {
        if (path.startsWith("/api/dev/")) {
            return true;
        }
        if (path.startsWith("/api/auth/")) {
            return true;
        }
        // WebSocket/SockJS upgrade and polling — auth handled at STOMP CONNECT frame level
        // Browser WebSocket API cannot send Authorization headers during HTTP upgrade
        return path.startsWith("/ws/") || path.equals("/ws");
    }

    private boolean isAuthorized(String path, String role) {
        if ("SUPER_ADMIN".equals(role)) return true; // super-admin has full access

        if (path.startsWith("/api/orders/")) {
            return "CLIENT".equals(role);
        }
        if (path.startsWith("/api/driver/")) {
            return "DRIVER".equals(role);
        }
        if (path.startsWith("/api/admin/routes/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);
        }
        if (path.startsWith("/api/admin/vehicles/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);
        }
        if (path.startsWith("/api/admin/reports/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role) || "MANAGER".equals(role);
        }
        if (path.startsWith("/api/admin/deliveries/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role) || "MANAGER".equals(role);
        }
        if (path.startsWith("/api/admin/users/")) {
            return "ADMIN".equals(role);
        }
        if (path.startsWith("/api/deliveries/")) {
            return "CLIENT".equals(role) || "DRIVER".equals(role) || "DISPATCHER".equals(role) || "ADMIN".equals(role);
        }
        if (path.startsWith("/api/users/")) {
            return "CLIENT".equals(role);
        }
        return true;
    }

    private Mono<Void> writeError(ServerHttpResponse response, HttpStatus status, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String payload = "{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(payload.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}

