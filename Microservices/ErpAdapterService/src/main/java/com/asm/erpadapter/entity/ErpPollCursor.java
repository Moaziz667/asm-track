package com.asm.erpadapter.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable per-tenant cursor for the inbound ERP change poll ({@code ErpChangeOrchestrator}).
 *
 * <p>The cursor used to live in a {@code ConcurrentHashMap}: every restart re-seeded it at the ERP's
 * "now", permanently skipping any order change made while the adapter was down. Persisting it makes
 * the poll resume exactly where it left off.
 */
@Entity
@Table(name = "erp_poll_cursor")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpPollCursor {

    @Id
    private UUID tenantId;

    @Column(name = "cursor_value", nullable = false, length = 64)
    private String cursorValue;

    private LocalDateTime updatedAt;
}
