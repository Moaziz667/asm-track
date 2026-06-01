package com.asm.driver.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbound shape for driver audit log entries. Mirrors the Delivery
 * microservice's {@code AuditLog} contract so the global /api/admin/audit
 * endpoint can merge both sources transparently.
 */
@Data
@Builder
public class DriverAuditLogResponse {
    private UUID id;
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
