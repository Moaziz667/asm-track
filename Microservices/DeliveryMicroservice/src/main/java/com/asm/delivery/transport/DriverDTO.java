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
}
