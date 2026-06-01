package com.asm.delivery.dto.response;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveMissionsDTO {
    private UUID activeDeliveryId;
    private UUID activeRouteId;
}
