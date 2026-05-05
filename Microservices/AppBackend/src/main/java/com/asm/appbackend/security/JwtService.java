package com.asm.appbackend.security;

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

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessExpiryMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expiry-ms}") long accessExpiryMs
    ) {
        this.key = Keys.hmacShaKeyFor(deriveHmacKey(secret));
        this.accessExpiryMs = accessExpiryMs;
    }

    private byte[] deriveHmacKey(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    public String generateClientToken(String subject, String name, Integer odooPartnerId) {
        var builder = Jwts.builder()
                .subject(subject)
                .claim("role", "CLIENT")
                .claim("name", name)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiryMs));

        if (odooPartnerId != null) {
            builder.claim("odooPartnerId", odooPartnerId);
        }

        return builder.signWith(key).compact();
    }

    public String generateAdminToken(String subject, String role, String name) {
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .claim("name", name)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiryMs))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(String subject, String role, String name) {
        long refreshExpiryMs = 7L * 24 * 60 * 60 * 1000;
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .claim("name", name)
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiryMs))
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

    public long getAccessExpiryMs() {
        return accessExpiryMs;
    }
}
