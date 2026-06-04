package com.asm.appbackend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

@Service
@Slf4j
public class EncryptionService {

    private SecretKeySpec secretKey;

    public EncryptionService(@Value("${AES_ENCRYPTION_KEY}") String secret) {
        try {
            // We use SHA-256 to ensure the key is exactly 256 bits (32 bytes) long,
            // regardless of the length of the string provided in the .env file.
            byte[] key = secret.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            key = sha.digest(key);
            key = Arrays.copyOf(key, 32); 
            secretKey = new SecretKeySpec(key, "AES");
            log.info("AES Encryption Service initialized securely.");
        } catch (Exception e) {
            log.error("Error initializing Encryption Service", e);
            throw new RuntimeException("Failed to initialize Encryption Service", e);
        }
    }

    private static final String GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    public String encrypt(String strToEncrypt) {
        if (strToEncrypt == null || strToEncrypt.isBlank()) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            byte[] ciphertext = cipher.doFinal(strToEncrypt.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Error while encrypting: {}", e.getMessage());
            throw new RuntimeException("Encryption failed");
        }
    }

    public String decrypt(String strToDecrypt) {
        if (strToDecrypt == null || strToDecrypt.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(strToDecrypt);
            if (decoded.length >= GCM_IV_LENGTH) {
                try {
                    byte[] iv = new byte[GCM_IV_LENGTH];
                    System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
                    byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
                    System.arraycopy(decoded, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
                    Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
                    javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv);
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
                    return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
                } catch (Exception gcmEx) {
                    log.debug("GCM decryption failed, trying ECB fallback...", gcmEx);
                }
            }
            // ECB Fallback
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Error while decrypting: {}", e.getMessage());
            throw new RuntimeException("Decryption failed");
        }
    }
}
