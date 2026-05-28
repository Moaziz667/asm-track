package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusHistoryResponse {
    private String        id;
    private String        status;
    private String        actor;
    private LocalDateTime timestamp;
    private String        changedBy;
    private String        changedByRole;
    private String        eventKey;
    private java.util.Map<String, Object> eventParams;
    private LocalDateTime changedAt;
}
