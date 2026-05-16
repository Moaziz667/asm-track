package com.asm.auth.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

@Configuration
@Slf4j
public class RsaKeyConfig {

    /**
     * Generates a reproducible RSA-2048 key pair from a seed string.
     * Same seed = same key pair across restarts → existing tokens stay valid.
     * Change AUTH_RSA_SEED env var to rotate all tokens (forces re-login).
     */
    @Bean
    public KeyPair rsaKeyPair(@Value("${auth.rsa-seed}") String seed) throws Exception {
        byte[] seedBytes = MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seedBytes);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, random);
        KeyPair keyPair = kpg.generateKeyPair();
        log.info("RSA key pair initialized — key-id=asm-auth-key-1");
        return keyPair;
    }

}
