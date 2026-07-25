package com.asm.erpadapter.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "idempotent_transaction")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotentTransaction {
    @Id
    private String transactionId;

    /**
     * The tenant that executed this transaction. transactionId is a caller-generated UUID (globally
     * unique in practice), but tenant isolation must not rest on caller convention alone: a cache hit
     * is only honored when the stored tenant matches the current one (see IdempotencyService).
     */
    private java.util.UUID tenantId;

    private String erpOrderId;
    
    private String status; // SUCCESS, FAILED
    
    // TEXT in schema.sql — a partial-delivery result JSON can exceed a small VARCHAR, and a failed
    // save silently disables idempotency for that tx.
    @Column(columnDefinition = "TEXT")
    private String responsePayload; // JSON of the result
    
    private LocalDateTime processedAt;
}
