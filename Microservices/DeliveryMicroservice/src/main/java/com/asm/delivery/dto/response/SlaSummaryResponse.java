package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class SlaSummaryResponse {

    private int onTime;
    private int atRisk;
    private int breached;
    private int total;

    /** AT_RISK stops (up to 10), ordered by etaAt ascending. */
    private List<SlaStopItem> atRiskStops;

    /** BREACHED stops (up to 10), ordered by etaAt ascending. */
    private List<SlaStopItem> breachedStops;

    @Data
    @Builder
    public static class SlaStopItem {
        private UUID stopId;
        private UUID deliveryId;
        private UUID routeId;
        private String clientName;
        private LocalDateTime etaAt;
        private LocalDateTime slaDeadline;
        private String slaStatus;
    }
}
