package com.asm.erpadapter.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Validates incoming calls using JWT Bearer token (client_credentials)
 * issued by auth-server. Replaces X-Internal-Secret header check.
 */
@Configuration
@Slf4j
public class SecurityConfig {

    @Value("${auth.server.jwks-uri}")
    private String jwksUri;

    private RSAPublicKey publicKey;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        RestTemplate rt = new RestTemplate();
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Map<String, Object> jwks = rt.getForObject(jwksUri, Map.class);
                List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
                Map<String, Object> key = keys.get(0);
                Base64.Decoder dec = Base64.getUrlDecoder();
                BigInteger modulus  = new BigInteger(1, dec.decode((String) key.get("n")));
                BigInteger exponent = new BigInteger(1, dec.decode((String) key.get("e")));
                this.publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                log.info("ErpAdapter RSA public key loaded from JWKS");
                return;
            } catch (Exception e) {
                log.warn("JWKS fetch attempt {}/5 failed: {} — retrying in 3s", attempt, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        throw new IllegalStateException("ErpAdapter could not fetch RSA public key from " + jwksUri);
    }

    @Bean
    public OncePerRequestFilter internalSecretFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                String path = request.getRequestURI();
                if (!path.startsWith("/api/")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing service token");
                    return;
                }

                try {
                    Claims claims = Jwts.parser()
                            .verifyWith(publicKey)
                            .build()
                            .parseSignedClaims(authHeader.substring(7))
                            .getPayload();

                    String role = claims.get("role", String.class);
                    if (!"SERVICE".equals(role)) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not a service token");
                        return;
                    }
                } catch (JwtException | IllegalArgumentException e) {
                    log.warn("Invalid service token on ErpAdapter: {}", e.getMessage());
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid service token");
                    return;
                }

                filterChain.doFilter(request, response);
            }
        };
    }
}
