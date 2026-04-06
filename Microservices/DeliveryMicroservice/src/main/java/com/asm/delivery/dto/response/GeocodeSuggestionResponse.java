package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GeocodeSuggestionResponse {
    private Double lat;
    private Double lng;
    private String displayName;
    /** City extracted from reverse geocode address (null for forward geocode). */
    private String city;
    /** Postal code extracted from reverse geocode address (null for forward geocode). */
    private String postalCode;
    private boolean found;
    private boolean outsideTunisiaBbox;
}
