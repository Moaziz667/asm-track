package com.asm.delivery.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Common shape returned by {@code GET /api/admin/audit} after merging
 * delivery-side and driver-side logs. The frontend AuditLogsPage already
 * expects this contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AuditLogView {
    private String id;
    private String actorId;
    private String actorName;
    private String actorRole;
    private String action;
    private String targetEntity;
    private String resourceId;
    private String details;
    private String ipAddress;
    private LocalDateTime createdAt;
}
