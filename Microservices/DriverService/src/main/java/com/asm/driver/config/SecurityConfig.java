package com.asm.driver.config;

import com.asm.driver.security.JwtAuthConverter;
import com.asm.driver.security.RbacAuthorizationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RbacAuthorizationManager rbac) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/auth/driver/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/internal/**").hasRole("SERVICE")   // service-to-service, not user-facing
                // Every application request is authorized by the single canonical policy
                // (rbac-policy.json) via RbacAuthorizationManager — no per-path rules re-encoded here.
                .anyRequest().access(rbac)
            )
            .oauth2ResourceServer(rs -> rs
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthConverter()))
            )
            .exceptionHandling(ex -> ex.accessDeniedHandler((req, res, e) -> {
                var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                org.slf4j.LoggerFactory.getLogger(SecurityConfig.class)
                        .warn("Access denied {} {} — authorities={}", req.getMethod(), req.getRequestURI(),
                                auth == null ? "anonymous" : auth.getAuthorities());
                res.setStatus(403);
                res.setContentType("application/json");
                res.getWriter().write("{\"status\":403,\"message\":\"Access denied\"}");
            }));

        return http.build();
    }

    @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @org.springframework.beans.factory.annotation.Value("${auth.issuer.url:${auth.server.url:http://keycloak:8080/realms/asm}}")
    private String issuerUri;

    @Bean
    public org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder jwtDecoder =
                org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> issuerValidator = jwt -> {
            java.net.URL issuer = jwt.getIssuer();
            if (issuer != null && issuer.toString().endsWith("/realms/asm")) {
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
            }
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error(
                            "invalid_token", "Invalid issuer: " + issuer, null));
        };

        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withIssuer =
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        new org.springframework.security.oauth2.jwt.JwtTimestampValidator(),
                        issuerValidator
                );

        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withAudience =
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        withIssuer,
                        jwt -> {
                            java.util.List<String> aud = jwt.getAudience();
                            String azp = jwt.getClaimAsString("azp");
                            
                            boolean hasValidAud = aud != null && (aud.contains("driver-app") || aud.contains("admin-web")
                                    || aud.contains("erp-adapter") || aud.contains("delivery-service")
                                    || aud.contains("driver-service") || aud.contains("app-backend"));

                            boolean hasValidAzp = azp != null && (azp.equals("driver-app") || azp.equals("admin-web")
                                    || azp.equals("erp-adapter") || azp.equals("delivery-service")
                                    || azp.equals("driver-service") || azp.equals("app-backend"));

                            if (hasValidAud || hasValidAzp) {
                                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
                            }
                            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                                    new org.springframework.security.oauth2.core.OAuth2Error(
                                            "invalid_token", "Required audience or azp is missing", null));
                        }
                );

        jwtDecoder.setJwtValidator(withAudience);
        return jwtDecoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
