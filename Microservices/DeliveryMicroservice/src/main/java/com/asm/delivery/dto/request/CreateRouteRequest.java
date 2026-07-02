package com.asm.delivery.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Route creation contract used by admin planning UI")
public class CreateRouteRequest {
    @Schema(description = "Route display name. Optional — when blank the server assigns the next sequential code (R001, R002, …).", example = "Tunis Morning Route")
    private String name;

    @NotNull
    @Schema(description = "Assigned driver id", example = "a1f5d102-9e5e-4b66-a605-2be9fb880ce1")
    private UUID driverId;

    @Schema(description = "Assigned vehicle id", example = "d1cc8a6f-7586-4d96-b682-99a31da24f0f")
    private UUID vehicleId;

    @NotNull
    @Schema(description = "Planned route date (local)", example = "2026-04-08")
    private LocalDate date;

    @Schema(description = "Planned route start time", example = "08:30:00")
    private LocalTime plannedStartTime;

    @Schema(description = "Planned route end time", example = "13:30:00")
    private LocalTime plannedEndTime;

    @Schema(description = "Operational city filter", example = "Tunis")
    private String city;

    @Schema(description = "Ordered delivery ids to insert as stops")
    private List<UUID> deliveryIds;

    @Schema(description = "Optional per-stop scheduling config")
    private List<StopConfig> stopConfigs;

    @Data
    @Schema(description = "Per-stop service window and SLA buffer")
    public static class StopConfig {
        @Schema(description = "Stop delivery id")
        private UUID deliveryId;

        @Schema(description = "Manual start window", example = "09:00:00")
        private java.time.LocalTime startTimeWindow;

        @Schema(description = "Manual end window", example = "10:00:00")
        private java.time.LocalTime endTimeWindow;

        @Schema(description = "Extra SLA buffer in minutes", example = "15")
        private int bufferMinutes;
    }

    /** Depot where the route departs from — required for ETA/optimization. */
    @NotNull(message = "Depot is required for ETA calculation")
    @Schema(description = "Departure depot id", example = "8f68c8de-77a0-4428-94f5-cf0e554fb36a")
    private UUID depotId;

    /** Optional explicit departure time; defaults to date + plannedStartTime. */
    @Schema(description = "Explicit departure datetime in local timezone", example = "2026-04-08T08:30:00")
    private java.time.LocalDateTime departureTime;
}
