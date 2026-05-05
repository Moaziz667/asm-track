package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ReorderRouteStopsRequest {
    @NotEmpty
    private List<UUID> stopIds;
}
