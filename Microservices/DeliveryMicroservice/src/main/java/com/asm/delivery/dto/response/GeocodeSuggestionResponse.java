package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GeocodeSuggestionResponse {
    private Double lat;
    private Double lng;
    private String displayName;
    /** City extracted from the geocoder address details (forward + reverse); null if unavailable. */
    private String city;
    /** Postal code extracted from the geocoder address details (forward + reverse); null if unavailable. */
    private String postalCode;
    private boolean found;
    private boolean outsideTunisiaBbox;
}
