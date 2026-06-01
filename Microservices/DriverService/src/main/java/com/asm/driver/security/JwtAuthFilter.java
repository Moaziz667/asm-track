package com.asm.driver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.jsonwebtoken.Claims;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverAccountStatus;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final DriverRepository driverRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        if (jwtService.isValid(token)) {
            Claims claims = jwtService.parseToken(token);
            String type = claims.get("type", String.class);

            if ("access".equals(type)) {
                String userId    = claims.getSubject();
                String role      = claims.get("role", String.class);

                if ("DRIVER".equalsIgnoreCase(role)) {
                    try {
                        UUID driverId = UUID.fromString(userId);
                        Optional<Driver> driverOpt = driverRepository.findById(driverId);
                        if (driverOpt.isEmpty() || driverOpt.get().getAccountStatus() != DriverAccountStatus.ACTIVE) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\": \"unauthorized\", \"message\": \"DRIVER_ACCOUNT_DISABLED\"}");
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\": \"unauthorized\", \"message\": \"INVALID_DRIVER_ID\"}");
                        return;
                    }
                }

                UserPrincipal principal = new UserPrincipal(userId, role);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
