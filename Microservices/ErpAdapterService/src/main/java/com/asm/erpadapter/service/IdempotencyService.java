package com.asm.erpadapter.service;

import com.asm.erpadapter.entity.IdempotentTransaction;
import com.asm.erpadapter.repository.IdempotentTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotentTransactionRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional
    public <T> T execute(String txId, String erpOrderId, Class<T> returnType, Supplier<T> operation) {
        if (txId == null || txId.isBlank()) {
            log.warn("ERP sync missing transactionId — erpOrderId={} retryable=true unsafe=true", erpOrderId);
            return operation.get();
        }

        Optional<IdempotentTransaction> existing = repo.findById(txId);
        if (existing.isPresent()) {
            IdempotentTransaction tx = existing.get();
            log.info("Idempotency hit — txId={} erpOrderId={} processedAt={} action=returning_cached",
                    txId, erpOrderId, tx.getProcessedAt());
            try {
                return deserialize(tx.getResponsePayload(), returnType);
            } catch (Exception e) {
                log.warn("Idempotency cache deserialize failed — txId={} erpOrderId={} reason={} action=re_executing",
                        txId, erpOrderId, e.getMessage());
            }
        }

        try {
            T result = operation.get();
            // Only cache successful results — caching a failure would block all future retries.
            if (isSuccessResult(result)) {
                saveResult(txId, erpOrderId, result);
            } else {
                log.warn("ERP sync non-success — txId={} erpOrderId={} result={} retryable=true action=not_caching",
                        txId, erpOrderId, result);
            }
            return result;
        } catch (Exception e) {
            log.error("ERP sync exception — txId={} erpOrderId={} errorClass={} reason={} retryable=true",
                    txId, erpOrderId, e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    private void saveResult(String txId, String erpOrderId, Object result) {
        try {
            IdempotentTransaction tx = IdempotentTransaction.builder()
                    .transactionId(txId)
                    .erpOrderId(erpOrderId)
                    .status("SUCCESS")
                    .responsePayload(objectMapper.writeValueAsString(result))
                    .processedAt(LocalDateTime.now())
                    .build();
            repo.save(tx);
        } catch (Exception e) {
            log.error("Failed to save idempotent record for {}", txId, e);
        }
    }

    private <T> T deserialize(String payload, Class<T> returnType) throws Exception {
        if (returnType.equals(Boolean.class) || returnType.equals(boolean.class)) {
            return (T) Boolean.valueOf(payload);
        }
        return objectMapper.readValue(payload, returnType);
    }

    /**
     * Returns true only if the result represents a successful ERP operation.
     * Boolean false and DTOs with success=false are treated as non-cacheable failures.
     */
    private boolean isSuccessResult(Object result) {
        if (result == null) return false;
        if (result instanceof Boolean b) return b;
        // For DTO results (e.g. ErpPartialDeliveryResultDTO), inspect the "success" field via JSON
        try {
            String json = objectMapper.writeValueAsString(result);
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Object success = map.get("success");
            if (success instanceof Boolean b) return b;
        } catch (Exception ignored) {}
        // Default: assume non-null non-Boolean results are successful
        return true;
    }
}

