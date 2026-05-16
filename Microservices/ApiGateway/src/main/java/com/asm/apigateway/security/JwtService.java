package com.asm.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Validates JWT tokens via RSA public key fetched from auth-server's JWKS endpoint.
 * Replaces the previous HMAC shared-secret approach — no JWT_SECRET needed.
 */
@Service
@Slf4j
public class JwtService {

    @Value("${auth.server.jwks-uri}")
    private String jwksUri;

    private RSAPublicKey publicKey;

    // Gateway is reactive (WebFlux) — use WebClient instead of RestTemplate
    private final WebClient webClient = WebClient.create();

    @PostConstruct
    public void init() {
        this.publicKey = fetchPublicKey();
        log.info("Gateway RSA public key loaded from JWKS: {}", jwksUri);
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @SuppressWarnings("unchecked")
    private RSAPublicKey fetchPublicKey() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                Map<String, Object> jwks = webClient.get()
                        .uri(jwksUri)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
                Map<String, Object> key = keys.get(0);
                Base64.Decoder dec = Base64.getUrlDecoder();
                BigInteger modulus  = new BigInteger(1, dec.decode((String) key.get("n")));
                BigInteger exponent = new BigInteger(1, dec.decode((String) key.get("e")));
                return (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            } catch (Exception e) {
                log.warn("Gateway JWKS fetch attempt {}/10 failed: {} — retrying in 3s", attempt, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        throw new IllegalStateException("Gateway could not fetch RSA public key from " + jwksUri);
    }
}
