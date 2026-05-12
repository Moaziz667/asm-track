package com.asm.delivery.dto.response;

import java.util.List;
import java.util.UUID;

public record GlobalSearchResponse(
        List<DeliveryResult> deliveries,
        List<RouteResult>    routes,
        List<VehicleResult>  vehicles,
        List<ZoneResult>     zones,
        List<DepotResult>    depots,
        List<DriverResult>   drivers
) {
    public record DeliveryResult(
            UUID   deliveryId,
            String erpOrderId,
            String clientName,
            String clientPhone,
            String dropoffCity,
            String status
    ) {}

    public record RouteResult(
            UUID   routeId,
            String name,
            String status,
            String date
    ) {}

    public record VehicleResult(
            UUID   vehicleId,
            String plate,
            String make,
            String model,
            String vehicleStatus
    ) {}

    public record ZoneResult(
            UUID   zoneId,
            String name,
            String description
    ) {}

    public record DepotResult(
            UUID   depotId,
            String name,
            String address
    ) {}

    public record DriverResult(
            String driverId,
            String name,
            String phone
    ) {}

    public static GlobalSearchResponse empty() {
        return new GlobalSearchResponse(
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()
        );
    }
}
