package com.asm.delivery.service;

import com.asm.delivery.dto.response.DepotResponse;
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
