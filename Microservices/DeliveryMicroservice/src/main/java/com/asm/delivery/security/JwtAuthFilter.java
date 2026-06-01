package com.asm.delivery.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.asm.delivery.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.annotation.Value;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.transport.DriverDTO;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TransportPort transportPort;

    @Value("${app.security.trust-gateway-headers:false}")
    private boolean trustGatewayHeaders;

    @Value("${app.security.gateway-secret:}")
    private String gatewaySecret;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         chain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // If the gateway has already validated the JWT, it will inject user headers.
        // Only trust those headers when trust-gateway-headers=true AND a matching
        // X-Gateway-Secret header is present (prevents header spoofing in dev/staging).
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Authorization header present or invalid - checking gateway headers");
            if (trustGatewayHeaders && org.springframework.util.StringUtils.hasText(gatewaySecret)) {
                String incomingSecret = request.getHeader("X-Gateway-Secret");
                if (gatewaySecret.equals(incomingSecret)) {
                    String userId = request.getHeader("X-User-Id");
                    String role   = request.getHeader("X-User-Role");
                    log.debug("Gateway headers verified: X-User-Id={}, X-User-Role={}", userId, role);
                    if (userId != null && role != null
                            && SecurityContextHolder.getContext().getAuthentication() == null) {
                        
                        if ("DRIVER".equalsIgnoreCase(role)) {
                            if (!isDriverActive(userId)) {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"error\": \"unauthorized\", \"message\": \"DRIVER_ACCOUNT_DISABLED\"}");
                                return;
                            }
                        }

                        String name      = request.getHeader("X-User-Name");
                        String odooStr   = request.getHeader("X-Odoo-Partner-Id");
                        Integer odooId   = odooStr != null ? Integer.valueOf(odooStr) : null;
                        log.debug("Setting authentication from gateway headers userId={} role={} name={}", userId, role, name);
                        UserPrincipal principal = new UserPrincipal(userId, role, name, null, odooId);
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } else {
                    log.warn("Gateway header present but X-Gateway-Secret missing or wrong — ignoring");
                }
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
            Integer odooPartnerId = claims.get("odooPartnerId", Integer.class);

            log.debug("JWT token valid: subject={} role={} name={}", subject, role, name);

            if (subject != null && role != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                
                if ("DRIVER".equalsIgnoreCase(role)) {
                    if (!isDriverActive(subject)) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\": \"unauthorized\", \"message\": \"DRIVER_ACCOUNT_DISABLED\"}");
                        return;
                    }
                }

                log.debug("Setting authentication from JWT token subject={} role={}", subject, role);
                UserPrincipal principal = new UserPrincipal(subject, role, name, phone, odooPartnerId);
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

    private boolean isDriverActive(String userId) {
        try {
            DriverDTO driver = transportPort.getDriver(userId);
            return driver != null && Boolean.TRUE.equals(driver.getActive());
        } catch (Exception e) {
            log.warn("Failed to check if driver is active for userId={}: {}", userId, e.getMessage());
            return true; // Fall back to true to avoid locking out driver if service is temporarily down
        }
    }
}
