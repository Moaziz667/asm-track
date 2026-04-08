package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.SlaStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class RouteStopResponse {
    private UUID id;
    private UUID deliveryId;
    private Integer stopOrder;
    private RouteStopStatus status;
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private String notes;
    private String deliveryAddress;
    private String deliveryCity;
    private String deliveryPostalCode;
    private String deliveryCountryCode;
    private BigDecimal dropoffLat;
    private BigDecimal dropoffLng;
    private boolean dropoffPinned;
    private String routeGeometry;
    private BigDecimal routeDistanceKm;
    private Integer routeDurationMinutes;
    private LocalDateTime routeEtaAt;
    private Integer transitSlaMinutesComputed;
    private String routeProvider;

    // ── ETA / SLA fields ─────────────────────────────────────────────────────────
    private LocalDateTime etaAt;
    private LocalDateTime slaDeadline;
    private SlaStatus slaStatus;
    private Integer driveDurationSeconds;
    private Integer driveDistanceMeters;
    private LocalDateTime actualArrivalAt;
    private Integer dwellMinutes;
    private Integer bufferMinutes;
    private java.time.LocalTime startTimeWindow;
    private java.time.LocalTime endTimeWindow;

    // ── Delivery-level fields for driver app inline display ───────────────────
    private String deliveryStatus;
    private String clientName;
    private String clientPhone;
    private java.math.BigDecimal totalAmount;
    private String orderRef;

    // ── Delay tracking for dashboard ──────────────────────────────────────────
    private Integer delayMinutes;           // negative = early, positive = late
    private String delayReason;             // "On time", "15 min early", "Failed stop (cascading)", etc.
    private String delayStatus;             // "ON_TIME", "EARLY", "LATE"

    // ── Legacy stop fields (reassigned/replanned) ─────────────────────────────
    private LocalDateTime removedAt;
    private String removedReason;           // "REASSIGNED" or "REPLANNED"
    private String removedBy;
}
