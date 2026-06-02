package com.asm.delivery.service;

import com.asm.delivery.dto.request.WarehouseDepotMappingRequest;
import com.asm.delivery.dto.response.WarehouseDepotMappingResponse;
import com.asm.delivery.entity.Depot;
import com.asm.delivery.entity.WarehouseDepotMapping;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DepotRepository;
import com.asm.delivery.repository.WarehouseDepotMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** CRUD for ERP-warehouse → ASM-depot mappings (used to resolve an order's source depot at import). */
@Service
@RequiredArgsConstructor
public class WarehouseDepotMappingService {

    private final WarehouseDepotMappingRepository repo;
    private final DepotRepository depotRepository;

    @Transactional(readOnly = true)
    public List<WarehouseDepotMappingResponse> list() {
        Map<UUID, String> depotNames = depotRepository.findAll().stream()
                .collect(Collectors.toMap(Depot::getId, Depot::getName, (a, b) -> a));
        return repo.findAll().stream()
                .map(m -> toResponse(m, depotNames.get(m.getDepotId())))
                .toList();
    }

    @Transactional
    public WarehouseDepotMappingResponse upsert(WarehouseDepotMappingRequest req) {
        if (!StringUtils.hasText(req.getWarehouseCode())) {
            throw AppException.badRequest("warehouseCode is required");
        }
        Depot depot = depotRepository.findById(req.getDepotId())
                .orElseThrow(() -> AppException.notFound("Depot not found"));

        String code = req.getWarehouseCode().trim();
        WarehouseDepotMapping mapping = repo.findByWarehouseCode(code).orElseGet(WarehouseDepotMapping::new);
        mapping.setWarehouseCode(code);
        mapping.setDepotId(depot.getId());
        mapping.setProvider(StringUtils.hasText(req.getProvider()) ? req.getProvider().trim() : null);
        return toResponse(repo.save(mapping), depot.getName());
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw AppException.notFound("Mapping not found");
        repo.deleteById(id);
    }

    private WarehouseDepotMappingResponse toResponse(WarehouseDepotMapping m, String depotName) {
        return WarehouseDepotMappingResponse.builder()
                .id(m.getId())
                .warehouseCode(m.getWarehouseCode())
                .depotId(m.getDepotId())
                .depotName(depotName)
                .provider(m.getProvider())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
