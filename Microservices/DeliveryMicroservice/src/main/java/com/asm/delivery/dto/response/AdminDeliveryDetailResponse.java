package com.asm.delivery.dto.response;

import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.OrderSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Detailed delivery contract used by admin details screen")
public class AdminDeliveryDetailResponse {
    @Schema(description = "Delivery unique identifier")
    private UUID deliveryId;

    @Schema(description = "Order unique identifier")
    private UUID orderId;

    @Schema(description = "Assigned route unique identifier")
    private UUID routeId;

    @Schema(description = "Assigned route name")
    private String routeName;

    @Schema(description = "Delivery status", example = "PICKED_UP")
    private String status;

    @Schema(description = "FORWARD (depot→client) or RETURN_PICKUP (client→depot, a return collection). ADR-033")
    private String kind;

    /** For RETURN_PICKUP: the RMA's own reference (RET-00001), shown instead of the shared order ref. */
    private String rmaNumber;

    /** For RETURN_PICKUP: the original (forward) delivery this return was collected against — clickable link. */
    private java.util.UUID originalDeliveryId;

    @Schema(description = "Failure classification when delivery failed", example = "ADDRESS_NOT_FOUND")
    private String failureCode;

    @Schema(description = "Admin failure-reason label (motif), without the driver's appended comment")
    private String failReason;

    @Schema(description = "Driver's free-text failure comment only (never the admin motif)")
    private String failureComment;

    @Schema(description = "Assigned driver id")
    private UUID driverId;

    @Schema(description = "Assigned driver name")
    private String driverName;

    @Schema(description = "Assigned driver phone")
    private String driverPhone;

    @Schema(description = "Order source", example = "ODOO")
    private OrderSource source;

    @Schema(description = "External ERP order id")
    private String erpOrderId;

    @Schema(description = "Human-readable ERP reference — used for backorders where erpOrderId is null (e.g. S00123/BO)")
    private String erpExternalRef;

    @Schema(description = "Client full name")
    private String clientName;

    @Schema(description = "Client phone")
    private String clientPhone;

    @Schema(description = "Client email")
    private String clientEmail;

    @Schema(description = "Dropoff address")
    private String dropoffAddress;

    @Schema(description = "Dropoff city")
    private String dropoffCity;

    @Schema(description = "Dropoff postal code")
    private String dropoffPostalCode;

    @Schema(description = "Dropoff country code", example = "TN")
    private String dropoffCountryCode;

    @Schema(description = "Dropoff latitude")
    private BigDecimal dropoffLat;

    @Schema(description = "Dropoff longitude")
    private BigDecimal dropoffLng;

    @Schema(description = "True if dropoff coordinates are pinned")
    private boolean dropoffPinned;

    @Schema(description = "Resolved zone id")
    private UUID zoneId;

    @Schema(description = "Resolved zone name")
    private String zoneName;

    @Schema(description = "Resolved zone color")
    private String zoneColor;

    @Schema(description = "Delivery instructions")
    private String deliveryInstructions;

    @Schema(description = "Order items snapshot")
    private List<OrderItem> items;

    @Schema(description = "Order total amount")
    private BigDecimal totalAmount;

    @Schema(description = "Order total weight in kg")
    private BigDecimal totalWeightKg;

    @Schema(description = "Route distance in km")
    private BigDecimal routeDistanceKm;

    @Schema(description = "Route duration in minutes")
    private Integer routeDurationMinutes;

    @Schema(description = "Computed transit SLA in minutes")
    private Integer transitSlaMinutesComputed;

    @Schema(description = "Route ETA")
    private LocalDateTime routeEtaAt;

    @Schema(description = "Route geometry as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    @Schema(description = "Routing provider", example = "OSRM")
    private String routeProvider;

    @Schema(description = "ISO currency code", example = "TND")
    private String currency;

    @Schema(description = "ERP sync status")
    private String odooSyncStatus;

    @Schema(description = "Backorder id in ERP if generated")
    private Integer odooBackorderId;

    @Schema(description = "Effective scheduled date/time (replan date if rescheduled, else ERP date)")
    private LocalDateTime scheduledAt;

    @Schema(description = "Replan date set by admin; non-null when the delivery was rescheduled")
    private LocalDateTime rescheduledAt;

    @Schema(description = "Delivery time-window start (HH:mm) from the active route stop")
    private String timeSlotStartTime;

    @Schema(description = "Delivery time-window end (HH:mm) from the active route stop")
    private String timeSlotEndTime;

    @Schema(description = "Official ERP delivery-note (bon de livraison) number")
    private String blNumber;

    @Schema(description = "ERP source-warehouse code from the delivery note")
    private String warehouseCode;

    @Schema(description = "Resolved source depot id (where goods are loaded)")
    private java.util.UUID sourceDepotId;

    @Schema(description = "Resolved source depot name")
    private String sourceDepotName;

    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime inTransitAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private LocalDateTime cancelledAt;

    @Schema(description = "True when POD exists for this delivery")
    private boolean podExists;

    @Schema(description = "True when this delivery already has an open return (REQUESTED/APPROVED/RECEIVED) "
            + "— lets the UI prevent a duplicate RMA proactively")
    private boolean hasOpenReturn;

    @Schema(description = "Status timeline for audit/tracking")
    private List<StatusHistoryResponse> statusHistory;

    @Schema(description = "All shipments sharing this order's sale-order ref (original + backorder(s) / "
            + "multi-depot splits), so the group is traceable from any one of them")
    private List<RelatedShipment> relatedShipments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "A sibling shipment under the same sale order")
    public static class RelatedShipment {
        private UUID deliveryId;
        private String blNumber;
        @Schema(description = "ERP order ref — shown when there's no BL yet (e.g. an ERPNext reliquat: S00018#R2)")
        private String erpOrderId;
        private String status;
        @Schema(description = "True for the delivery currently being viewed")
        private boolean current;
    }
}
