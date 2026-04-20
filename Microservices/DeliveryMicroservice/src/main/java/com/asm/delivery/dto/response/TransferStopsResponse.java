package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferStopsResponse {

    private UUID targetRouteId;
    
    private List<UUID> transferredStopIds;
    
    private String sourceRouteStatus;
    
    private List<Warning> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Warning {
        private UUID stopId;
        private String code;
        private String detail;
    }
}
