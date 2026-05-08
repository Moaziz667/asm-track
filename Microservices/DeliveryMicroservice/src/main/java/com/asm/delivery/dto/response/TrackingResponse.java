package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TrackingResponse {
    private String deliveryId;
    private String status;
    private String clientName;
    private Double dropoffLat;
    private Double dropoffLng;
    private String dropoffAddress;
    private String dropoffCity;
    private String driverName;
    private String driverPhone;
    private Double driverLat;
    private Double driverLng;
    private Double depotLat;
    private Double depotLng;
    private String depotName;
    private String startWindow;
    private String endWindow;
    private String etaAt;
    private String routeGeometry;
    private String companyName;
    private String companyLogoUrl;
}
