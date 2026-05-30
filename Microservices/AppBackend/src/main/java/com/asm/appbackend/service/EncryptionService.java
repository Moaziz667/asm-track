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

    public String encrypt(String strToEncrypt) {
        if (strToEncrypt == null || strToEncrypt.isBlank()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(strToEncrypt.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("Error while encrypting: {}", e.getMessage());
            throw new RuntimeException("Encryption failed");
        }
    }

    public String decrypt(String strToDecrypt) {
        if (strToDecrypt == null || strToDecrypt.isBlank()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return new String(cipher.doFinal(Base64.getDecoder().decode(strToDecrypt)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Error while decrypting: {}", e.getMessage());
            throw new RuntimeException("Decryption failed");
        }
    }
}
