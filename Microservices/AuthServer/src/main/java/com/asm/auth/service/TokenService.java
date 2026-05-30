package com.asm.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class TokenService {

    private final KeyPair keyPair;
    private final long accessExpiryMs;
    private final long refreshExpiryMs;

    public TokenService(
            KeyPair keyPair,
            @Value("${auth.access-token-expiry}") long accessExpirySec,
            @Value("${auth.refresh-token-expiry}") long refreshExpirySec) {
        this.keyPair       = keyPair;
        this.accessExpiryMs  = accessExpirySec  * 1000L;
        this.refreshExpiryMs = refreshExpirySec * 1000L;
    }

    /**
     * Issues an access token for a validated user.
     * Preserves the same claim structure as the existing HMAC-based JwtService
     * so downstream services need only change key validation, not claim parsing.
     */
    public String issueAccessToken(Map<String, Object> user) {
        var builder = Jwts.builder()
                .subject((String) user.get("id"))
                .claim("role",  user.get("role"))
                .claim("name",  user.get("name"))
                .claim("type",  "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiryMs));

        if (user.containsKey("phone") && user.get("phone") != null)
            builder.claim("phone", user.get("phone"));

        return builder.signWith(keyPair.getPrivate()).compact();
    }

    /**
     * Issues a refresh token — minimal claims, just enough to re-validate.
     */
    public String issueRefreshToken(Map<String, Object> user) {
        return Jwts.builder()
                .subject((String) user.get("id"))
                .claim("role", user.get("role"))
                .claim("type", "refresh")
                .claim("userType", user.get("type")) // "admin" or "driver"
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiryMs))
                .signWith(keyPair.getPrivate())
                .compact();
    }

    /**
     * Issues a short-lived service token for client credentials flow.
     */
    public String issueServiceToken(String clientId) {
        return Jwts.builder()
                .subject(clientId)
                .claim("role", "SERVICE")
                .claim("type", "service")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 5 * 60 * 1000L)) // 5 min
                .signWith(keyPair.getPrivate())
                .compact();
    }

    /**
     * Parses and validates a token (used for refresh token validation).
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessExpiryMs() { return accessExpiryMs; }
}
