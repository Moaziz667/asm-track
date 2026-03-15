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

        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
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

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id", userId)
                    .header("X-User-Role", normalizedRole)
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT parse/validation failed: {}", ex.getMessage());
            return writeError(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }

    private boolean isPublic(String path) {
        return path.startsWith("/api/auth/");
    }

    private boolean isAuthorized(String path, String role) {
        if (path.startsWith("/api/orders/")) {
            return "CLIENT".equals(role);
        }
        if (path.startsWith("/api/driver/")) {
            return "DRIVER".equals(role);
        }
        if (path.startsWith("/api/deliveries/")) {
            return "CLIENT".equals(role) || "DRIVER".equals(role);
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
