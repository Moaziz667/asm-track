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

    private String erpOrderId;
    
    private String status; // SUCCESS, FAILED
    
    @Column(length = 2000)
    private String responsePayload; // JSON of the result
    
    private LocalDateTime processedAt;
}
