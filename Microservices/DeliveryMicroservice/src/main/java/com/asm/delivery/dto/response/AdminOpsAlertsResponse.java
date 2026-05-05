package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOpsAlertsResponse {
    private LocalDateTime generatedAt;
    private String period;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private AdminOpsOverviewResponse.SlaSnapshot sla;
    private List<AdminOpsOverviewResponse.ExceptionRow> alerts;
}
