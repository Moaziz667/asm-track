package com.asm.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Zone heatmap payload — per-zipcode order density with zone identity")
public class ZoneHeatmapResponse {

    @Schema(description = "Heatmap points grouped by zipcode within zones")
    private List<ZipcodeHeatpoint> points;

    @Schema(description = "Order count per zone id over the immediately-preceding window of equal length "
            + "(Tunis-anchored); present when compare=true. Used to compute the per-zone delta.")
    private Map<String, Long> previousOrdersByZone;

    @Data
    @Builder
    @Schema(description = "Single zipcode density point")
    public static class ZipcodeHeatpoint {
        @Schema(description = "Postal code", example = "1000")
        private String zipcode;

        @Schema(description = "Centroid latitude of orders in this zipcode")
        private double lat;

        @Schema(description = "Centroid longitude of orders in this zipcode")
        private double lng;

        @Schema(description = "Total order count for this zipcode in the period")
        private long ordersCount;

        @Schema(description = "Delayed/failed order count for this zipcode")
        private long delayedOrders;

        @Schema(description = "Parent zone UUID")
        private UUID zoneId;

        @Schema(description = "Parent zone name", example = "Grand Tunis")
        private String zoneName;

        @Schema(description = "Parent zone hex color", example = "#2563EB")
        private String zoneColor;
    }
}
