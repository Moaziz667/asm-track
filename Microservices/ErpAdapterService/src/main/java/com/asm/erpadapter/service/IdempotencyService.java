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

    /**
     * Execute an operation with idempotency protection.
     *
     * <p>Transaction scope is split into three phases to avoid holding a DB connection
     * during long-running Odoo RPC calls:
     * <ol>
     *   <li>Phase 1: Check cache (transactional, fast)</li>
     *   <li>Phase 2: Execute operation (no transaction)</li>
     *   <li>Phase 3: Save result (transactional, fast)</li>
     * </ol>
     */
    public <T> T execute(String txId, String erpOrderId, Class<T> returnType, Supplier<T> operation) {
        if (txId == null || txId.isBlank()) {
            log.warn("ERP sync missing transactionId — erpOrderId={} retryable=true unsafe=true", erpOrderId);
            return operation.get();
        }

        // Phase 1: Check cache (fast, transactional)
        Optional<IdempotentTransaction> existing = findExisting(txId);
        if (existing.isPresent()) {
            IdempotentTransaction tx = existing.get();
            // Tenant guard: the store is shared across tenants (single H2), and txIds are
            // caller-generated. A record written by another tenant must NEVER satisfy this tenant's
            // replay check — that would return one tenant's ERP response payload to another.
            java.util.UUID currentTenant = com.asm.erpadapter.security.TenantContext.get();
            if (tx.getTenantId() != null && !tx.getTenantId().equals(currentTenant)) {
                log.error("Idempotency txId COLLISION across tenants — txId={} storedTenant={} currentTenant={} "
                        + "action=ignoring_cached_re_executing", txId, tx.getTenantId(), currentTenant);
            } else {
                log.info("Idempotency hit — txId={} erpOrderId={} processedAt={} action=returning_cached",
                        txId, erpOrderId, tx.getProcessedAt());
                try {
                    return deserialize(tx.getResponsePayload(), returnType);
                } catch (Exception e) {
                    log.warn("Idempotency cache deserialize failed — txId={} erpOrderId={} reason={} action=re_executing",
                            txId, erpOrderId, e.getMessage());
                }
            }
        }

        // Phase 2: Execute operation (no transaction — Odoo RPC can take 5-15s)
        try {
            T result = operation.get();
            // Phase 3: Save result (fast, transactional)
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

    @Transactional
    protected Optional<IdempotentTransaction> findExisting(String txId) {
        return repo.findById(txId);
    }

    @Transactional
    protected void saveResult(String txId, String erpOrderId, Object result) {
        try {
            IdempotentTransaction tx = IdempotentTransaction.builder()
                    .transactionId(txId)
                    .tenantId(com.asm.erpadapter.security.TenantContext.get())
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

