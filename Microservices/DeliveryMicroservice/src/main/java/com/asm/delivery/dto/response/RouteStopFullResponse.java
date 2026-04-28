package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.SlaStatus;
import com.asm.delivery.dto.response.DeliveryResponse;
import com.asm.delivery.dto.response.OrderResponse;
import com.asm.delivery.dto.response.ProofOfDeliveryResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.List;

@Data
@Builder
@Schema(description = "Route stop full details including nested delivery and order information")
public class RouteStopFullResponse {
    @Schema(description = "Route stop id")
    private UUID id;

    @Schema(description = "Linked delivery id")
    private UUID deliveryId;

    @Schema(description = "Sequence order in route")
    private Integer stopOrder;

    @Schema(description = "Route stop execution status")
    private RouteStopStatus status;
    
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private String notes;
    
    @Schema(description = "Stop delivery address")
    private String deliveryAddress;

    @Schema(description = "Stop delivery city")
    private String deliveryCity;

    @Schema(description = "Stop postal code")
    private String deliveryPostalCode;

    @Schema(description = "Stop country code")
    private String deliveryCountryCode;

    @Schema(description = "Dropoff latitude")
    private BigDecimal dropoffLat;

    @Schema(description = "Dropoff longitude")
    private BigDecimal dropoffLng;

    @Schema(description = "True when coordinates are pinned")
    private boolean dropoffPinned;

    @Schema(description = "Geometry to this stop as JSON string [[lat,lng], ...]")
    private String routeGeometry;

    @Schema(description = "Distance to stop in km")
    private BigDecimal routeDistanceKm;

    @Schema(description = "Duration to stop in minutes")
    private Integer routeDurationMinutes;

    @Schema(description = "ETA for this stop")
    private LocalDateTime routeEtaAt;

    @Schema(description = "Computed transit SLA in minutes")
    private Integer transitSlaMinutesComputed;

    @Schema(description = "Routing provider")
    private String routeProvider;

    @Schema(description = "Delivery status SLA")
    private SlaStatus slaStatus;

    @Schema(description = "Calculated delay in minutes")
    private Integer delayMinutes;

    @Schema(description = "Delay status based on SLA deadline and ETA")
    private String delayStatus;

    @Schema(description = "Reason for delay, e.g., Started late, Dwell delay")
    private String delayReason;

    @Schema(description = "Manual start time window")
    private LocalTime startTimeWindow;

    @Schema(description = "Manual end time window")
    private LocalTime endTimeWindow;

    // The nested actual delivery and order payload
    private DeliveryResponse delivery;
    private OrderResponse order;
    
    // In-line evidence
    private ProofOfDeliveryResponse proofOfDelivery;
    
    // Full audit history trail
    private List<StatusHistoryResponse> statusHistory;
    // ── Legacy stop fields (reassigned/replanned) ─────────────────────────────
    @Schema(description = "Timestamp when stop was removed from active route")
    private LocalDateTime removedAt;
    @Schema(description = "Removal reason", example = "REASSIGNED")
    private String removedReason;           // "REASSIGNED" or "REPLANNED"
    @Schema(description = "Actor that removed the stop")
    private String removedBy;
    @Schema(description = "Inlined client name for simple display")
    private String clientName;
    @Schema(description = "Inlined order reference (ERP/Odoo) for simple display")
    private String orderRef;
}
