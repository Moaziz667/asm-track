package com.asm.delivery.service;

import com.asm.delivery.dto.response.GlobalSearchResponse;
import com.asm.delivery.dto.response.GlobalSearchResponse.*;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DepotRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.repository.ZoneRepository;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GlobalSearchService {

    private final DeliveryRepository deliveryRepo;
    private final RouteRepository    routeRepo;
    private final VehicleRepository  vehicleRepo;
    private final ZoneRepository     zoneRepo;
    private final DepotRepository    depotRepo;
    private final TransportPort      transportPort;

        @Transactional(readOnly = true)
        public GlobalSearchResponse search(String q, int limit) {
        var qLower = q.toLowerCase();
        
        // 1. Transactional DB queries
        var results = doSearch(q, limit);

                // 2. Drivers scoped by company deliveries (companyFilter applies)
                Set<UUID> driverIds = deliveryRepo.findDistinctDriverIds(Arrays.asList(DeliveryStatus.values()));
                var drivers = driverIds.stream()
                                .map(id -> transportPort.getDriver(id.toString()))
                                .filter(d -> d != null)
                                .filter(d -> (d.getName() != null && d.getName().toLowerCase().contains(qLower))
                                                  || (d.getPhone() != null && d.getPhone().contains(q)))
                                .limit(limit)
                                .map(d -> new DriverResult(d.getId(), d.getName(), d.getPhone()))
                                .toList();

        return new GlobalSearchResponse(results.deliveries(), results.routes(), results.vehicles(), results.zones(), results.depots(), drivers);
    }

    @Transactional(readOnly = true)
    public SearchResults doSearch(String q, int limit) {
        var page = PageRequest.of(0, limit);

        var deliveries = deliveryRepo.searchByQuery(q, page).stream()
                .map(d -> new DeliveryResult(
                        d.getId(),
                        d.getOrder() != null ? d.getOrder().getErpOrderId() : null,
                        d.getOrder() != null ? d.getOrder().getClientName() : null,
                        d.getOrder() != null ? d.getOrder().getClientPhone() : null,
                        d.getOrder() != null ? d.getOrder().getDropoffCity() : null,
                        d.getStatus().name()))
                .toList();

        var routes = routeRepo.searchByQuery(q, page).stream()
                .map(r -> new RouteResult(
                        r.getId(),
                        r.getName(),
                        r.getStatus().name(),
                        r.getDate().toString()))
                .toList();

        var vehicles = vehicleRepo.searchByQuery(q, page).stream()
                .map(v -> new VehicleResult(
                        v.getId(),
                        v.getPlate(),
                        v.getMake(),
                        v.getModel(),
                        v.getVehicleStatus().name()))
                .toList();

        var zones = zoneRepo.searchByQuery(q, page).stream()
                .map(z -> {
                    String cities = z.getCities() != null && !z.getCities().isEmpty()
                            ? String.join(", ", z.getCities().stream().limit(3).toList())
                            : z.getDescription();
                    return new ZoneResult(z.getId(), z.getName(), cities);
                })
                .toList();

        var depots = depotRepo.searchByQuery(q, page).stream()
                .map(d -> new DepotResult(
                        d.getId(),
                        d.getName(),
                        d.getAddress()))
                .toList();

        return new SearchResults(deliveries, routes, vehicles, zones, depots);
    }

    private record SearchResults(
            java.util.List<DeliveryResult> deliveries,
            java.util.List<RouteResult> routes,
            java.util.List<VehicleResult> vehicles,
            java.util.List<ZoneResult> zones,
            java.util.List<DepotResult> depots
    ) {}
}
