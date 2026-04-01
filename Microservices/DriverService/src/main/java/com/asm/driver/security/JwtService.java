package com.asm.driver.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessExpiration;
    private final long refreshExpiration;

    public JwtService(
            @Value("${app.security.jwt.secret}") String secret,
            @Value("${app.security.jwt.access-expiration}") long accessExpiration,
            @Value("${app.security.jwt.refresh-expiration}") long refreshExpiration) {
        this.key = Keys.hmacShaKeyFor(deriveHmacKey(secret)); 
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
    }

    private byte[] deriveHmacKey(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    public String generateAccessToken(String subject, String role, Map<String, Object> extraClaims) {
        return buildToken(subject, role, "access", extraClaims, accessExpiration);
    }

    public String generateRefreshToken(String subject, String role) {
        return buildToken(subject, role, "refresh", Map.of(), refreshExpiration);
    }

    private String buildToken(String subject, String role, String type, Map<String, Object> extraClaims, long expiration) {
        return Jwts.builder()
                .subject(subject)
                .claims(extraClaims)
                .claim("role", role)
                .claim("type", type)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
