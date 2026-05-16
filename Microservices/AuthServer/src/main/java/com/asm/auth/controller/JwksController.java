package com.asm.auth.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
public class JwksController {

    private final Map<String, Object> jwksResponse;

    public JwksController(KeyPair keyPair) {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();

        byte[] modBytes = pub.getModulus().toByteArray();
        if (modBytes[0] == 0) {
            byte[] trimmed = new byte[modBytes.length - 1];
            System.arraycopy(modBytes, 1, trimmed, 0, trimmed.length);
            modBytes = trimmed;
        }

        Map<String, Object> jwk = Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", "asm-auth-key-1",
                "n",   enc.encodeToString(modBytes),
                "e",   enc.encodeToString(pub.getPublicExponent().toByteArray())
        );
        this.jwksResponse = Map.of("keys", List.of(jwk));
    }

    @GetMapping(value = "/oauth2/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return jwksResponse;
    }
}
