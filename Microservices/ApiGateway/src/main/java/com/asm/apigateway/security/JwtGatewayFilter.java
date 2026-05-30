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
            if (!isAuthorized(path, normalizedRole, exchange.getRequest().getMethod())) {
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
        if (path.startsWith("/api/public/")) {
            return true;
        }
        // WebSocket/SockJS upgrade and polling — auth handled at STOMP CONNECT frame level
        // Browser WebSocket API cannot send Authorization headers during HTTP upgrade
        return path.startsWith("/ws/") || path.equals("/ws");
    }

    private boolean isAuthorized(String path, String role, HttpMethod method) {
        if ("ADMIN".equals(role)) return true;

        // Client-only endpoints
        if (path.startsWith("/api/orders/")) {
            return "CLIENT".equals(role);
        }
        if (path.startsWith("/api/users/")) {
            return "CLIENT".equals(role);
        }

        // Driver-only endpoints
        if (path.startsWith("/api/driver/")) {
            return "DRIVER".equals(role);
        }

        // Admin sub-paths with narrower role sets — must be checked before the /api/admin/ catch-all

        // /api/admin/companies/me — ADMIN can read their own company
        if (path.equals("/api/admin/companies/me")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);
        }
        // All other company endpoints — ADMIN only
        if (path.startsWith("/api/admin/companies/")) {
            return "ADMIN".equals(role);
        }
        // Drivers are platform-owned (shared across companies).
        // GET (list/read) → ADMIN + DISPATCHER need this to assign drivers to routes.
        // Mutations (create, update, activate, reset-password) → ADMIN only.
        if (path.startsWith("/api/admin/drivers")) {
            return HttpMethod.GET.equals(method)
                    && ("ADMIN".equals(role) || "DISPATCHER".equals(role));
        }
        // Vehicles are platform-owned (managed by ADMIN).
        // Read access (GET) → Dispatchers & Managers.
        // Mutations (create, update, delete, status change) → ADMIN only.
        if (path.startsWith("/api/admin/vehicles")) {
            return HttpMethod.GET.equals(method)
                    && ("ADMIN".equals(role) || "DISPATCHER".equals(role));
        }
        // ERP order import (pending-orders, import-order) is core dispatcher work.
        // ERP credentials are stored at company level via AppBackend — not exposed here.
        if (path.startsWith("/api/admin/erp/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);
        }
        // System settings (SLA limits, config) — ADMIN only, not DISPATCHER or MANAGER.
        if (path.startsWith("/api/admin/reports/settings")) {
            return "ADMIN".equals(role);
        }
        // ADMIN + DISPATCHER + MANAGER
        if (path.startsWith("/api/admin/stats") || path.startsWith("/api/admin/reports/") || path.startsWith("/api/admin/ops/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role) || "MANAGER".equals(role);
        }
        // MANAGER gets read-only access to routes and deliveries for operational oversight.
        if ("MANAGER".equals(role) && HttpMethod.GET.equals(method)) {
            return path.startsWith("/api/admin/routes") || path.startsWith("/api/admin/deliveries");
        }
        // ADMIN + DISPATCHER (general admin catch-all covers routes, deliveries, erp imports, etc.)
        if (path.startsWith("/api/admin/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role);
        }

        // Shared delivery tracking — no CLIENT
        if (path.startsWith("/api/deliveries/")) {
            return "DRIVER".equals(role) || "DISPATCHER".equals(role) || "ADMIN".equals(role);
        }

        // v1 endpoints (depots, optimization)
        if (path.startsWith("/api/v1/")) {
            return "ADMIN".equals(role) || "DISPATCHER".equals(role) || "MANAGER".equals(role);
        }

        return false; // deny by default
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

