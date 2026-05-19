package com.asm.driver.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates internal service-to-service calls using a JWT Bearer token
 * issued by auth-server via the client_credentials grant.
 * Replaces the previous X-Internal-Secret shared header approach.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!request.getRequestURI().startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Internal endpoint called without Bearer token: {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"error\":\"Missing service token\"}");
            return;
        }

        try {
            Claims claims = jwtService.parseToken(authHeader.substring(7));
            String role = claims.get("role", String.class);
            if (!"SERVICE".equals(role)) {
                log.warn("Internal endpoint called with non-service token role={}", role);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("{\"error\":\"Not a service token\"}");
                return;
            }
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid service token on internal endpoint: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"error\":\"Invalid service token\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
