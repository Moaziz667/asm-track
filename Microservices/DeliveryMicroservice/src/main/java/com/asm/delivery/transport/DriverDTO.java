package com.asm.delivery.transport;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDTO {
    private String  id;
    private String  name;
    private String  phone;
    private Double  currentLat;
    private Double  currentLng;
    private String  lastLocationAt;
    private String  createdAt;
    private String  fcmToken;
    private Boolean active;
    private String  onlineStatus;
    /** Id of the driver's active route today (VALIDATED/IN_PROGRESS), if any. Populated by the
     *  fleet-drivers endpoint (the driver service doesn't own routes); null when the driver is free. */
    private String  activeRouteId;
}
