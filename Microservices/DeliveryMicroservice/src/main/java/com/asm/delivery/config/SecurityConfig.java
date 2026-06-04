package com.asm.delivery.config;

import com.asm.delivery.security.JwtAuthConverter;
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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                    "/api/auth/driver/**",
                    "/api/public/**",
                    "/api/dev/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/v3/api-docs"
                ).permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/api/orders/**").hasRole("CLIENT")
                .requestMatchers("/api/driver/deliveries/**").hasRole("DRIVER")
                .requestMatchers("/api/driver/profile/**").hasRole("DRIVER")
                .requestMatchers("/api/driver/location/**").hasRole("DRIVER")
                .requestMatchers("/api/driver/**").hasRole("DRIVER")
                .requestMatchers("/api/admin/stats").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/admin/reports/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/admin/ops/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/internal/**").hasRole("SERVICE")
                .requestMatchers(HttpMethod.GET, "/api/admin/routes/**", "/api/admin/routes",
                        "/api/admin/deliveries/**", "/api/admin/deliveries")
                        .hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/admin/companies/me").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "DISPATCHER")
                .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/deliveries/**").hasAnyRole("DRIVER", "DISPATCHER", "ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(rs -> rs
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthConverter()))
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
