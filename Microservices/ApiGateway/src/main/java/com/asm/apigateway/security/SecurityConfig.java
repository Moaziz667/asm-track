package com.asm.apigateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final List<String> ROLE_PRIORITY =
            List.of("ADMIN", "DISPATCHER", "MANAGER", "DRIVER", "CLIENT", "SERVICE");

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(ServerHttpSecurity.CorsSpec::disable)
            .authorizeExchange(auth -> auth
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // /api/erp/inbound/** = Odoo webhook: no JWT (Odoo can't present one); the erp-adapter
                // gates it with the shared X-Webhook-Secret header instead.
                .pathMatchers("/api/auth/**", "/api/public/**", "/api/dev/**", "/ws/**", "/api/erp/inbound/**").permitAll()
                .pathMatchers("/internal/**").denyAll()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(rs -> rs
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter()))
                .bearerTokenConverter(new CookieBearerTokenConverter())
            );
        return http.build();
    }

    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null) return Flux.empty();
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.get("roles");
            if (roles == null) return Flux.empty();
            return Flux.fromIterable(roles)
                    .filter(r -> ROLE_PRIORITY.contains(r.toUpperCase()))
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()));
        });
        return converter;
    }

    /** Hard-blocks /internal/** before Spring Security even evaluates auth. */
    @Bean
    @Order(Integer.MIN_VALUE)
    public WebFilter blockInternalEndpointsFilter() {
        return (exchange, chain) -> {
            if (exchange.getRequest().getURI().getPath().startsWith("/internal/")) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                var buf = exchange.getResponse().bufferFactory()
                        .wrap("{\"status\":403,\"message\":\"Direct access to internal endpoints is blocked\"}"
                                .getBytes(StandardCharsets.UTF_8));
                return exchange.getResponse().writeWith(Mono.just(buf));
            }
            return chain.filter(exchange);
        };
    }
}
