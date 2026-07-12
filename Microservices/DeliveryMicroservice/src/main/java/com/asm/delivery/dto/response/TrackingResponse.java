package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class TrackingResponse {
    private String deliveryId;
    private String status;
    /** Human-readable failure reason (label + optional comment); null unless status is FAILED/PARTIALLY_DELIVERED. */
    private String failReason;
    /** Latest return lifecycle state for this delivery (RmaStatus name), or null if no return exists. */
    private String returnStatus;
    /** Resolution note for the latest return when it was REJECTED — the reason shown to the client. */
    private String returnResolutionNote;
    private String clientName;
    private String clientPhone;
    private String erpOrderId;
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
    private Double totalAmount;
    private List<OrderItemDto> items;

    @Data
    @Builder
    public static class OrderItemDto {
        private String name;
        private Integer quantity;
        private Double unitPrice;
    }
}
