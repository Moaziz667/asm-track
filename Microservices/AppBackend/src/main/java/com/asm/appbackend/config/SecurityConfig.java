package com.asm.appbackend.config;

import com.asm.appbackend.security.JwtAuthConverter;
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
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/**",
                                "/actuator/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/v3/api-docs"
                        ).permitAll()
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers("/api/profile/**").hasRole("CLIENT")
                        .requestMatchers("/api/admin/users", "/api/admin/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/clients/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "DISPATCHER", "MANAGER")
                        .anyRequest().authenticated()
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

    @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @org.springframework.beans.factory.annotation.Value("${auth.issuer.url:${auth.server.url:http://keycloak:8080/realms/asm}}")
    private String issuerUri;

    @Bean
    @org.springframework.context.annotation.Primary
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
                            
                            boolean hasValidAud = aud != null && (aud.contains("admin-web") || aud.contains("erp-adapter")
                                    || aud.contains("delivery-service") || aud.contains("driver-service"));
                                    
                            boolean hasValidAzp = azp != null && (azp.equals("admin-web") || azp.equals("erp-adapter")
                                    || azp.equals("delivery-service") || azp.equals("driver-service"));

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

    /**
     * Dedicated decoder for OIDC back-channel <b>logout tokens</b>. These are signed by the realm just
     * like access tokens, but carry the JOSE header {@code typ: logout+jwt}, which the default Nimbus
     * type verifier rejects. We accept that typ here (and only here) and validate signature + issuer;
     * the logout-specific {@code events} claim is checked in {@code BackChannelLogoutController}.
     */
    @Bean("logoutTokenJwtDecoder")
    public org.springframework.security.oauth2.jwt.JwtDecoder logoutTokenJwtDecoder() {
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
                org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                        .jwtProcessorCustomizer(p -> p.setJWSTypeVerifier(
                                new com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier<>(
                                        new com.nimbusds.jose.JOSEObjectType("logout+jwt"),
                                        com.nimbusds.jose.JOSEObjectType.JWT,
                                        null)))
                        .build();

        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> issuerValidator = jwt -> {
            java.net.URL issuer = jwt.getIssuer();
            if (issuer != null && issuer.toString().endsWith("/realms/asm")) {
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
            }
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error(
                            "invalid_token", "Invalid issuer: " + issuer, null));
        };
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                new org.springframework.security.oauth2.jwt.JwtTimestampValidator(),
                issuerValidator));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
