package com.asm.delivery.service;

import com.asm.delivery.dto.request.PatchDepotLocationRequest;
import com.asm.delivery.dto.response.DepotResponse;
import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.entity.Depot;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DepotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only depot access. Depot rows are ERP-owned (synced from Odoo warehouses by
 * {@link DepotSyncService}); they are no longer created/updated/deleted from the admin UI.
 */
@Service
@RequiredArgsConstructor
public class DepotService {

    private final DepotRepository depotRepository;
    private final GeocodingService geocodingService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<DepotResponse> list() {
        return depotRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DepotResponse> listActive() {
        return depotRepository.findByIsActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DepotResponse get(UUID id) {
        return toResponse(getDepot(id));
    }

    public Depot getDepot(UUID id) {
        return depotRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("Depot not found"));
    }

    @Transactional
    public DepotResponse geolocate(UUID id) {
        Depot depot = getDepot(id);
        if (depot.getAddress() == null || depot.getAddress().isBlank()) {
            throw AppException.badRequest("Depot has no address to geocode");
        }
        GeocodeSuggestionResponse geo = geocodingService.geocodeGlobal(depot.getAddress());
        if (geo == null || !geo.isFound()) {
            throw AppException.badRequest("Address could not be geocoded");
        }
        depot.setLatitude(geo.getLat());
        depot.setLongitude(geo.getLng());
        Depot saved = depotRepository.save(depot);
        // Actor resolved from the SecurityContext (request thread) inside logAction.
        auditLogService.logAction(null, "GEOLOCATE_DEPOT", "DEPOT", id.toString(),
                java.util.Map.of("name", depot.getName() != null ? depot.getName() : "",
                        "lat", String.valueOf(geo.getLat()), "lng", String.valueOf(geo.getLng())));
        return toResponse(saved);
    }

    @Transactional
    public DepotResponse updateLocation(UUID id, PatchDepotLocationRequest req) {
        Depot depot = getDepot(id);
        if (req.getLatitude() != null) {
            depot.setLatitude(req.getLatitude());
        }
        if (req.getLongitude() != null) {
            depot.setLongitude(req.getLongitude());
        }
        if (req.getAddress() != null) {
            depot.setAddress(req.getAddress());
        }
        Depot saved = depotRepository.save(depot);
        auditLogService.logAction(null, "UPDATE_DEPOT_LOCATION", "DEPOT", id.toString(),
                java.util.Map.of("name", depot.getName() != null ? depot.getName() : "",
                        "lat", String.valueOf(depot.getLatitude()), "lng", String.valueOf(depot.getLongitude()),
                        "address", depot.getAddress() != null ? depot.getAddress() : ""));
        return toResponse(saved);
    }

    private DepotResponse toResponse(Depot depot) {
        return DepotResponse.builder()
                .id(depot.getId())
                .name(depot.getName())
                .address(depot.getAddress())
                .warehouseCode(depot.getWarehouseCode())
                .erpWarehouseId(depot.getErpWarehouseId())
                .provider(depot.getProvider())
                .latitude(depot.getLatitude())
                .longitude(depot.getLongitude())
                .isActive(depot.getIsActive())
                .createdAt(depot.getCreatedAt())
                .updatedAt(depot.getUpdatedAt())
                .build();
    }
}
