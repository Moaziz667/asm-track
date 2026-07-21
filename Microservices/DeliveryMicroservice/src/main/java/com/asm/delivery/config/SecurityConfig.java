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
                    "/api/v1/auth/driver/**",
                    "/api/v1/public/**",
                    "/api/v1/dev/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/v3/api-docs"
                ).permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/v1/orders/**").hasRole("CLIENT")
                .requestMatchers("/api/v1/driver/deliveries/**").hasRole("DRIVER")
                .requestMatchers("/api/v1/driver/profile/**").hasRole("DRIVER")
                .requestMatchers("/api/v1/driver/location/**").hasRole("DRIVER")
                .requestMatchers("/api/v1/driver/**").hasRole("DRIVER")
                .requestMatchers("/api/v1/admin/stats").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/v1/admin/reports/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/v1/admin/ops/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/internal/**").hasRole("SERVICE")
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/routes/**", "/api/v1/admin/routes",
                        "/api/v1/admin/deliveries/**", "/api/v1/admin/deliveries")
                        .hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/v1/admin/companies/me").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "DISPATCHER")
                .requestMatchers("/api/v1/depots/**", "/api/v1/zones/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                .requestMatchers("/api/v1/deliveries/**").hasAnyRole("DRIVER", "DISPATCHER", "ADMIN")
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
