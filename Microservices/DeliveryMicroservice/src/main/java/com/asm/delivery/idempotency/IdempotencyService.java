package com.asm.delivery.idempotency;
 
import com.asm.delivery.entity.ProcessedRequest;
import com.asm.delivery.repository.ProcessedRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final ProcessedRequestRepository repository;
    private final ObjectMapper objectMapper;

    public record CacheEntry(String fingerprint, Object response) {}

    @Transactional(readOnly = true)
    public CacheEntry get(String scope, String key) {
        String fullKey = hash(scope + ":" + key);
        return repository.findById(fullKey)
                .map(pr -> {
                    try {
                        return new CacheEntry(null, pr.getResponseBody()); 
                    } catch (Exception e) {
                        return null;
                    }
                }).orElse(null);
    }

    @Transactional
    public void put(String scope, String key, String fingerprint, Object response, int ttlSeconds) {
        String fullKey = hash(scope + ":" + key);
        try {
            String body = (response instanceof String) ? (String) response : objectMapper.writeValueAsString(response);
            ProcessedRequest pr = ProcessedRequest.builder()
                    .idempotencyKey(fullKey)
                    .responseBody(body)
                    .build();
            repository.save(pr);
        } catch (Exception e) {
            log.error("Failed to save idempotency key: {}", fullKey, e);
        }
    }

    private String hash(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}

