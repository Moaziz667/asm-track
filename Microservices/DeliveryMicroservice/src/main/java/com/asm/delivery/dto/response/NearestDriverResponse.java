package com.asm.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One driver ranked by proximity to a delivery's drop-off, for the quick-reassign picker.
 * {@code source} tells the client whether the ranking is road-accurate ({@code osrm}) or a
 * straight-line fallback ({@code haversine}); {@code etaSeconds}/{@code distanceMeters} are null
 * when a leg is unroutable or OSRM is disabled.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Driver ranked by road proximity to a delivery drop-off")
public class NearestDriverResponse {
    private String driverId;
    private String name;
    private String onlineStatus;
    private Double currentLat;
    private Double currentLng;

    @Schema(description = "Road travel time driver → drop-off, in seconds (null if unroutable)", example = "360")
    private Integer etaSeconds;

    @Schema(description = "Road distance driver → drop-off, in metres (null if unavailable)", example = "1200")
    private Integer distanceMeters;

    @Schema(description = "Ranking source", allowableValues = {"osrm", "haversine"})
    private String source;
}
