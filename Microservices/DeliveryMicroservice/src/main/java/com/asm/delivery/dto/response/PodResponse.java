package com.asm.delivery.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PodResponse {
    private UUID          id;
    private UUID          deliveryId;
    private String        comment;
    private LocalDateTime collectedAt;
    private BigDecimal    lat;
    private BigDecimal    lng;

    // Only populated for admin endpoint
    private String photoBase64;
    private String signatureBase64;
}
