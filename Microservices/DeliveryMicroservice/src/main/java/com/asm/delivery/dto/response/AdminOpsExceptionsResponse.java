package com.asm.delivery.dto.response;

import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.FailureCode;
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
public class AdminOpsExceptionsResponse {
    private LocalDateTime generatedAt;
    private String period;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private long total;
    private List<ExceptionItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExceptionItem {
        private UUID deliveryId;
        private UUID orderId;
        private UUID routeId;
        private String routeName;
        private DeliveryStatus status;
        private FailureCode failureCode;
        private String motif;
        private UUID driverId;
        private String driverName;
        private String clientName;
        private String city;
        private String severity;
        private String comment;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
