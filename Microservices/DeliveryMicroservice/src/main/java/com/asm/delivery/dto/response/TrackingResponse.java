package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class TrackingResponse {
    private String deliveryId;
    private String status;
    /** FORWARD (depot→client) or RETURN_PICKUP (client→depot). ADR-033 — lets the tracking UI flip the
     *  pickup/destination roles for a return collection. */
    private String kind;
    /** Human-readable failure reason (label + optional comment); null unless status is FAILED/PARTIALLY_DELIVERED. */
    private String failReason;
    /** Latest return lifecycle state for this delivery (RmaStatus name), or null if no return exists. */
    private String returnStatus;
    /** Resolution note for the latest return when it was REJECTED — the reason shown to the client. */
    private String returnResolutionNote;
    private String clientName;
    private String clientPhone;
    private String erpOrderId;
    /**
     * The recipient's own order reference.
     *
     * <p>The one number on this page they recognise: erpOrderId is the distributor's delivery-note
     * number and means nothing to the person waiting at the door.
     */
    private String customerRef;
    private Double dropoffLat;
    private Double dropoffLng;
    private String dropoffAddress;
    private String dropoffCity;
    private String driverName;
    private String driverPhone;
    /**
     * The driver's thumbnail, when he has one.
     *
     * <p>Published on the same footing as his name and phone, which this response already carried:
     * a recipient about to open his door to a stranger is better served by a face than by two
     * initials. Like the live position, it is withheld once the delivery is finished — see below.
     */
    private String driverPhotoUrl;
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
