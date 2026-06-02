package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class WarehouseDepotMappingResponse {
    private UUID id;
    private String warehouseCode;
    private UUID depotId;
    private String depotName;
    private String provider;
    private LocalDateTime updatedAt;
}
