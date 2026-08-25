package com.asm.assistant.config;

import com.asm.assistant.security.JwtAuthConverter;
import com.asm.assistant.security.RbacAuthorizationManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URL;
import java.util.List;

/**
 * Resource-server security, inherited verbatim from the platform: Keycloak JWKS signature check,
 * issuer + audience/azp validation, stateless, RBAC by realm role. No new identity system.
 *
 * <p>Phase 1 gates every {@code /api/assistant/**} call behind authentication and the operator roles.
 * When the assistant endpoints are added to the shared {@code rbac-policy.json}, this can delegate to
 * {@code RbacAuthorizationManager} like the other services; role-gating is the safe interim.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, RbacAuthorizationManager rbac) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/actuator/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/v3/api-docs"
                        ).permitAll()
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        // Every application request is authorized by the single canonical policy
                        // (rbac-policy.json) via RbacAuthorizationManager — no per-path rules
                        // re-encoded here. The policy already says what this service needs:
                        // /api/assistant/ requires an authenticated caller, since the answer is
                        // restricted at the source (every retrieval and every live tool call is
                        // scoped to the caller's tenant and permissions), while
                        // /api/assistant/admin requires perm:settings:manage.
                        .anyRequest().access(rbac)
                )
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthConverter()))
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"status\":401,\"message\":\"Authentication required\"}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"status\":403,\"message\":\"Access denied\"}");
                        })
                );
        return http.build();
    }

    @Bean
    @Primary
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> issuerValidator = jwt -> {
            URL issuer = jwt.getIssuer();
            if (issuer != null && issuer.toString().endsWith("/realms/asm")) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid issuer: " + issuer, null));
        };

        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            List<String> aud = jwt.getAudience();
            String azp = jwt.getClaimAsString("azp");
            boolean okAud = aud != null && (aud.contains("admin-web") || aud.contains("assistant-service")
                    || aud.contains("delivery-service") || aud.contains("driver-service")
                    || aud.contains("app-backend"));
            boolean okAzp = azp != null && (azp.equals("admin-web") || azp.equals("assistant-service")
                    || azp.equals("delivery-service") || azp.equals("driver-service")
                    || azp.equals("app-backend"));
            return (okAud || okAzp)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "Required audience or azp is missing", null));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), issuerValidator, audienceValidator));
        return decoder;
    }
}
