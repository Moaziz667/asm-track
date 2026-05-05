package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferStopsRequest {

    @NotNull
    private UUID sourceRouteId;

    private UUID targetRouteId; // null = create new DRAFT route

    private UUID targetDriverId; // required when targetRouteId is null

    private UUID targetVehicleId; // optional override

    @NotEmpty
    private List<UUID> stopIds; // must be non-empty

    private Integer insertAtOrder; // 1-indexed; null = append

    private String reason; // required if any stop is PICKED_UP

    @Builder.Default
    private Boolean acknowledgeWarnings = false; // must be true to override time-window conflicts
}
