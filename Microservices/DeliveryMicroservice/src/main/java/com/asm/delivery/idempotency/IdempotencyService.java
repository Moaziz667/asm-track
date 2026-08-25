package com.asm.delivery.idempotency;

import com.asm.delivery.entity.ProcessedRequest;
import com.asm.delivery.repository.ProcessedRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the answer already given to a request, so replaying it returns that answer instead of
 * acting a second time. The key is scoped to the method, the path and the caller before being
 * hashed — two drivers replaying the same offline write never collide.
 *
 * <p>Entries are pruned by {@link ProcessedRequestCleanupJob}, one sweep per tenant schema. Its
 * window ({@code idempotency.cleanup.ttl-hours}, 24 h) is deliberately the driver app's
 * offline-queue TTL: past it the app dead-letters the write instead of replaying it, so there is
 * nothing left to recognise.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final ProcessedRequestRepository repository;
    private final ObjectMapper objectMapper;

    /** The serialised response already returned for this key, or null the first time it is seen. */
    @Transactional(readOnly = true)
    public String get(String scope, String key) {
        return repository.findById(hash(scope + ":" + key))
                .map(ProcessedRequest::getResponseBody)
                .orElse(null);
    }

    @Transactional
    public void put(String scope, String key, Object response) {
        String fullKey = hash(scope + ":" + key);
        try {
            String body = (response instanceof String s) ? s : objectMapper.writeValueAsString(response);
            repository.save(ProcessedRequest.builder()
                    .idempotencyKey(fullKey)
                    .responseBody(body)
                    .build());
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
