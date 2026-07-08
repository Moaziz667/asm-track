package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a route's final state, generated at close time.
 * Stored as JSON in {@code route_report.payload} — never recomputed after close.
 */
@Data
@Builder
public class RouteReportResponse {

    private Header header;
    private Kpis kpis;
    private List<StatusBucket> statusBreakdown;
    private List<TimelinePoint> timeline;
    private List<StopRow> stops;
    private List<MovementEvent> movements;
    private String geometry;          // routeGeometry JSON [[lat,lng], ...]
    private List<PodEntry> podGallery;
    private List<AuditEntry> auditTrail;
    private LocalDateTime generatedAt;

    // ── Sub-types ─────────────────────────────────────────────────────────────

    @Data @Builder
    public static class Header {
        private UUID routeId;
        private String routeName;
        private LocalDate date;
        private UUID driverId;
        private String driverName;
        private UUID vehicleId;
        private String vehiclePlate;
        private String vehicleType;
        private String depotName;
        private String status;          // always CLOSED
        private LocalDateTime startedAt;
        private LocalDateTime closedAt;
        private Integer durationMinutes;
        private LocalTime plannedStartTime;
        private LocalTime plannedEndTime;
    }

    @Data @Builder
    public static class Kpis {
        // Stop counts
        private int totalStopsPlanned;     // attempted + removed
        private int attemptedStops;        // completed + partial + failed + failed_attempt
        private int completedStops;
        private int partialStops;
        private int failedStops;
        private int failedAttemptStops;
        private int replannedStops;
        private int cancelledStopsCount;

        // Rates (% with 2 decimals)
        private BigDecimal completionRate;  // (completed + partial) / attemptedStops
        private BigDecimal onTimeRate;      // onTimeCompleted / completedStops

        // Time / distance
        private int lateStops;
        private int earlyStops;
        private int onTimeStops;
        private Integer cumulativeDelayMinutes;
        private BigDecimal totalDistanceKm;
        private Integer activeDurationMinutes;
        private Integer routeStartDelayMinutes;
    }

    @Data @Builder
    public static class StatusBucket {
        private String label;          // "Livrés", "Échoués", "Partiels", "Replanifiés", "Annulés"
        private String key;            // COMPLETED | FAILED_ALL | PARTIAL | REPLANNED | CANCELLED
        private int count;
        private BigDecimal percentage;
    }

    @Data @Builder
    public static class TimelinePoint {
        private Integer stopOrder;
        private String clientName;
        private LocalTime plannedEnd;       // endTimeWindow
        private LocalDateTime actualAt;     // completedAt
        private Integer delayMinutes;       // positive = late
        private String classification;      // ON_TIME | LATE | EARLY | PARTIAL | FAILED | FAILED_ATTEMPT | REPLANNED | CANCELLED
    }

    @Data @Builder
    public static class StopRow {
        private UUID stopId;
        private UUID deliveryId;
        private Integer stopOrder;
        private String orderRef;        // human-readable ERP reference (e.g. WH/OUT/00268, S00091)
        private String clientName;
        private String address;
        private String city;
        private LocalTime startTimeWindow;
        private LocalTime endTimeWindow;
        private LocalDateTime arrivedAt;
        private LocalDateTime completedAt;
        private Integer delayMinutes;
        private Integer dwellMinutes;
        private String completionStatus;    // OK | KO | null
        private String finalStatus;         // RouteStopStatus.name()
        private String classification;      // same enum as TimelinePoint
        private boolean hasPod;

        // Movement (removed / handoff)
        private String movement;            // REPLANNED | CANCELLED | HANDOFF | null
        private String movementTarget;      // destination route name / new driver name
        private LocalDateTime removedAt;
        private String removedReason;
        private String removedBy;
        private LocalDateTime handoffConfirmedAt;
        private String handoffFromDriverName;
        private String handoffToDriverName;

        // Failure details
        private String failureCode;
        private String failReason;

        /** Per-line item breakdown (ordered / delivered / short). Null for pickup stops. */
        private java.util.List<ItemLine> items;
    }

    /** One order line in the per-delivery detail: ordered vs delivered, plus any shortfall dispositions. */
    @Data @Builder
    public static class ItemLine {
        private String sku;
        private String name;
        private Integer quantity;       // ordered
        private Integer quantityDone;   // delivered
        private java.util.List<ItemShortfall> shortfalls;   // non-delivered slices (missing/refused/damaged)
    }

    @Data @Builder
    public static class ItemShortfall {
        private String disposition;     // MISSING | REFUSED | DAMAGED
        private int quantity;
        private String reasonLabel;     // human motif (snapshotted from the failure-reason catalog)
    }

    @Data @Builder
    public static class MovementEvent {
        private LocalDateTime at;
        private String type;            // STOP_REMOVED_REPLANNED | STOP_REMOVED_CANCELLED | HANDOFF_CONFIRMED | STOP_FAILED | DELIVERY_CANCELLED
        private Integer stopOrder;
        private String clientName;
        private String actor;           // admin email / driver name / SYSTEM
        private String detail;          // human readable text in French
    }

    @Data @Builder
    public static class PodEntry {
        private UUID stopId;
        private UUID deliveryId;
        private Integer stopOrder;
        private String clientName;
        private String photoUrl;
        private String signatureUrl;
        private String bonLivraisonUrl;
        private BigDecimal lat;
        private BigDecimal lng;
        private LocalDateTime collectedAt;
        private String comment;
    }

    @Data @Builder
    public static class AuditEntry {
        private LocalDateTime at;
        private Integer stopOrder;      // the stop this event belongs to (for the Réf column)
        private String orderRef;        // human ERP reference of that stop's order
        private String actor;
        private String role;
        private String actionKey;       // raw event key — the admin UI maps it to a localized label (fr/en/ar)
        private String action;          // event label only (no "Arrêt #N ·" prefix — the column carries it)
        private java.util.Map<String, String> detailParams; // resolved values (route/driver/reason/note) — labelled by the UI
        private String detail;          // French detail string (fallback for old snapshots / non-localizing clients)
    }
}
