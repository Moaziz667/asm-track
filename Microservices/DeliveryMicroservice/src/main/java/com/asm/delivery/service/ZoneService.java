package com.asm.delivery.service;

import com.asm.delivery.dto.request.ZoneRequest;
import com.asm.delivery.dto.response.ZoneResponse;
import com.asm.delivery.entity.Zone;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.ZoneRepository;
import com.asm.delivery.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ZoneService {

    private final ZoneRepository zoneRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<ZoneResponse> list() {
        return zoneRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ZoneResponse> listActive() {
        return zoneRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ZoneResponse get(UUID id) {
        return toResponse(getZone(id));
    }

    @Transactional
    public ZoneResponse create(UserPrincipal principal, ZoneRequest request) {
        Zone zone = Zone.builder()
                .name(request.getName().trim())
                .color(request.getColor())
                .description(request.getDescription())
                .cities(request.getCities() != null ? request.getCities() : new ArrayList<>())
                .postalCodes(request.getPostalCodes() != null ? request.getPostalCodes() : new ArrayList<>())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .geometry(request.getGeometry())
                .build();
        Zone saved = zoneRepository.save(zone);
        auditLogService.logAction(principal, "CREATE_ZONE", "ZONE", saved.getId().toString(),
                java.util.Map.of("zone", saved.getName(), "action", "Creation de zone"));
        return toResponse(saved);
    }

    @Transactional
    public ZoneResponse update(UUID id, UserPrincipal principal, ZoneRequest request) {
        Zone zone = getZone(id);
        zone.setName(request.getName().trim());
        if (request.getColor() != null) {
            zone.setColor(request.getColor());
        }
        if (request.getDescription() != null) {
            zone.setDescription(request.getDescription());
        }
        if (request.getCities() != null) {
            zone.setCities(request.getCities());
        }
        if (request.getPostalCodes() != null) {
            zone.setPostalCodes(request.getPostalCodes());
        }
        if (request.getIsActive() != null) {
            zone.setIsActive(request.getIsActive());
        }
        if (request.getGeometry() != null) {
            zone.setGeometry(request.getGeometry());
        }
        Zone saved = zoneRepository.save(zone);
        auditLogService.logAction(principal, "UPDATE_ZONE", "ZONE", saved.getId().toString(),
                java.util.Map.of("zone", saved.getName(), "action", "Mise a jour de zone"));
        return toResponse(saved);
    }

    /** Soft delete — sets isActive = false. Hard delete is not allowed. */
    @Transactional
    public void delete(UUID id, UserPrincipal principal) {
        Zone zone = getZone(id);
        auditLogService.logAction(principal, "DELETE_ZONE", "ZONE", id.toString(),
                java.util.Map.of("zone", zone.getName(), "action", "Desactivation de zone"));
        zone.setIsActive(false);
        zoneRepository.save(zone);
    }

    public Zone getZone(UUID id) {
        return zoneRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("Zone not found"));
    }

    public ZoneResponse toResponse(Zone zone) {
        return ZoneResponse.builder()
                .id(zone.getId())
                .name(zone.getName())
                .color(zone.getColor())
                .description(zone.getDescription())
                .cities(zone.getCities())
                .postalCodes(zone.getPostalCodes())
                .isActive(zone.getIsActive())
                .geometry(zone.getGeometry())
                .createdAt(zone.getCreatedAt())
                .updatedAt(zone.getUpdatedAt())
                .build();
    }
}
