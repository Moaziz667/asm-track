package com.asm.driver.config;

import com.asm.driver.security.JwtAuthConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/driver/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/internal/**").hasRole("SERVICE")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/driver/**").hasRole("DRIVER")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(rs -> rs
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthConverter()))
            );

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

        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withIssuer =
                org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(issuerUri);

        org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withAudience =
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        withIssuer,
                        jwt -> {
                            java.util.List<String> aud = jwt.getAudience();
                            String azp = jwt.getClaimAsString("azp");
                            
                            boolean hasValidAud = aud != null && (aud.contains("driver-app") || aud.contains("admin-web")
                                    || aud.contains("erp-adapter") || aud.contains("delivery-service")
                                    || aud.contains("driver-service"));
                                    
                            boolean hasValidAzp = azp != null && (azp.equals("driver-app") || azp.equals("admin-web")
                                    || azp.equals("erp-adapter") || azp.equals("delivery-service")
                                    || azp.equals("driver-service"));

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

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        return new RestTemplate(factory);
    }
}
