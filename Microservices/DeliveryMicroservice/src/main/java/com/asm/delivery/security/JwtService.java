package com.asm.delivery.security;

import io.jsonwebtoken.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class JwtService {

    @Value("${auth.server.jwks-uri}")
    private String jwksUri;

    private RSAPublicKey publicKey;

    @PostConstruct
    public void init() {
        this.publicKey = fetchPublicKey();
        log.info("RSA public key loaded from JWKS: {}", jwksUri);
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try { parseToken(token); return true; }
        catch (JwtException | IllegalArgumentException e) { return false; }
    }

    public String getSubject(String token) { return parseToken(token).getSubject(); }

    public String getRole(String token) { return parseToken(token).get("role", String.class); }

    @SuppressWarnings("unchecked")
    private RSAPublicKey fetchPublicKey() {
        RestTemplate rt = new RestTemplate();
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Map<String, Object> jwks = rt.getForObject(jwksUri, Map.class);
                List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
                Map<String, Object> key = keys.get(0);
                Base64.Decoder dec = Base64.getUrlDecoder();
                BigInteger modulus  = new BigInteger(1, dec.decode((String) key.get("n")));
                BigInteger exponent = new BigInteger(1, dec.decode((String) key.get("e")));
                return (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            } catch (Exception e) {
                log.warn("JWKS fetch attempt {}/5 failed: {} — retrying in 3s", attempt, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        throw new IllegalStateException("Could not fetch RSA public key from " + jwksUri);
    }
}
