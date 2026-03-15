package com.asm.delivery.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         chain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // If the gateway has already validated the JWT, it will inject user headers.
        // Accept those headers as a trusted source of identity.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Authorization header present or invalid - checking gateway headers");
            String userId = request.getHeader("X-User-Id");
            String role = request.getHeader("X-User-Role");
            log.debug("Gateway headers: X-User-Id={}, X-User-Role={}", userId, role);
            if (userId != null && role != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                log.debug("Setting authentication from gateway headers userId={} role={}", userId, role);
                UserPrincipal principal = new UserPrincipal(userId, role, null, null);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            if (!jwtService.isValid(token)) {
                log.debug("JWT token is invalid or expired: {}", token);
                chain.doFilter(request, response);
                return;
            }

            String subject = jwtService.getSubject(token);
            String role    = jwtService.getRole(token);
            var    claims  = jwtService.parseToken(token);
            String name    = claims.get("name",  String.class);
            String phone   = claims.get("phone", String.class);

            log.debug("JWT token valid: subject={} role={} name={} phone={}", subject, role, name, phone);

            if (subject != null && role != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                log.debug("Setting authentication from JWT token subject={} role={}", subject, role);
                UserPrincipal principal = new UserPrincipal(subject, role, name, phone);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT validation failed: {}", ex.getMessage());
            // Invalid token — proceed without authentication; Spring Security will block protected routes
        }

        chain.doFilter(request, response);
    }
}
