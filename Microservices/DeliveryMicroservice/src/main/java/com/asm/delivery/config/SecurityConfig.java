package com.asm.delivery.config;

import com.asm.delivery.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(
                    "/api/auth/driver/**",
                    "/api/public/**",
                    "/api/dev/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/v3/api-docs"
                ).permitAll()
                // Client-only routes
                .requestMatchers("/api/orders/**").hasRole("CLIENT")
                // Driver-only routes
                .requestMatchers("/api/driver/deliveries/**").hasRole("DRIVER")
                .requestMatchers("/api/driver/profile/**").hasRole("DRIVER")
                .requestMatchers("/api/driver/location/**").hasRole("DRIVER")
                .requestMatchers("/api/driver/**").hasRole("DRIVER")
                // Admin stats — also allowed for MANAGER
                .requestMatchers("/api/admin/stats").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/admin/reports/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/admin/ops/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                // Internal service-to-service endpoints — OAuth2 service token required
                .requestMatchers("/internal/**").hasRole("SERVICE")
                // MANAGER read-only on routes and deliveries (must come before the /api/admin/** catch-all)
                .requestMatchers(HttpMethod.GET, "/api/admin/routes/**", "/api/admin/routes",
                        "/api/admin/deliveries/**", "/api/admin/deliveries")
                        .hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/admin/companies/me").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                // Admin endpoints — ADMIN + DISPATCHER
                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "DISPATCHER")
                // v1 endpoints (depots, optimization)
                .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                // WebSocket/SockJS — permit all at HTTP level; auth is in STOMP CONNECT frame
                .requestMatchers("/ws/**").permitAll()
                // Deliveries — driver, dispatcher, admin (no direct CLIENT access)
                .requestMatchers("/api/deliveries/**").hasAnyRole("DRIVER", "DISPATCHER", "ADMIN")
                // Anything else requires authentication
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
