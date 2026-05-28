package com.asm.delivery.dto.response;

import com.asm.delivery.entity.DeliveryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOpsAuditResponse {
    private LocalDateTime generatedAt;
    private String period;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private long total;
    private List<AuditEvent> events;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditEvent {
        private UUID historyId;
        private UUID deliveryId;
        private UUID orderId;
        private DeliveryStatus status;
        private String changedBy;
        private String changedByRole;
        private String eventKey;
        private java.util.Map<String, Object> eventParams;
        private String clientName;
        private String city;
        private LocalDateTime changedAt;
    }
}