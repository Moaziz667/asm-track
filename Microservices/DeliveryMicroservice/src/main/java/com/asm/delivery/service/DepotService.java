package com.asm.delivery.service;

import com.asm.delivery.dto.request.DepotRequest;
import com.asm.delivery.dto.response.DepotResponse;
import com.asm.delivery.entity.Depot;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DepotRepository;
import com.asm.delivery.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DepotService {

    private final DepotRepository depotRepository;
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

    @Transactional
    public DepotResponse create(UserPrincipal principal, DepotRequest request) {
        Depot depot = Depot.builder()
                .name(request.getName().trim())
                .address(request.getAddress())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();
        Depot saved = depotRepository.save(depot);
        auditLogService.logAction(principal, "CREATE_DEPOT", "DEPOT", saved.getId().toString(),
                java.util.Map.of("depot", saved.getName(), "adresse", saved.getAddress() != null ? saved.getAddress() : "", "action", "Creation de depot"));
        return toResponse(saved);
    }

    @Transactional
    public DepotResponse update(UUID id, UserPrincipal principal, DepotRequest request) {
        Depot depot = getDepot(id);
        depot.setName(request.getName().trim());
        depot.setAddress(request.getAddress());
        depot.setLatitude(request.getLatitude());
        depot.setLongitude(request.getLongitude());
        if (request.getIsActive() != null) {
            depot.setIsActive(request.getIsActive());
        }
        Depot saved = depotRepository.save(depot);
        auditLogService.logAction(principal, "UPDATE_DEPOT", "DEPOT", saved.getId().toString(),
                java.util.Map.of("depot", saved.getName(), "adresse", saved.getAddress() != null ? saved.getAddress() : "", "action", "Mise a jour de depot"));
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id, UserPrincipal principal) {
        Depot depot = getDepot(id);
        auditLogService.logAction(principal, "DELETE_DEPOT", "DEPOT", id.toString(),
                java.util.Map.of("depot", depot.getName(), "action", "Suppression de depot"));
        depotRepository.delete(depot);
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
                .latitude(depot.getLatitude())
                .longitude(depot.getLongitude())
                .isActive(depot.getIsActive())
                .createdAt(depot.getCreatedAt())
                .updatedAt(depot.getUpdatedAt())
                .build();
    }
}
