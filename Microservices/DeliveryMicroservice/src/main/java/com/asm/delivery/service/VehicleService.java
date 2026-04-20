package com.asm.delivery.service;

import com.asm.delivery.dto.request.AssignVehicleRequest;
import com.asm.delivery.dto.request.CreateVehicleRequest;
import com.asm.delivery.dto.request.UpdateVehicleRequest;
import com.asm.delivery.dto.response.VehicleResponse;
import com.asm.delivery.entity.Vehicle;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final RouteRepository routeRepository;
    private final MinioStorageService minioStorageService;
    private final AuditLogService auditLogService;

        // DRAFT routes are planning-only — vehicle remains allocatable until the route is VALIDATED
        private static final List<RouteStatus> ACTIVE_ROUTE_STATUSES = List.of(
            RouteStatus.VALIDATED,
            RouteStatus.IN_PROGRESS
        );

    @Transactional(readOnly = true)
    public List<VehicleResponse> list() {
        Set<UUID> busyVehicleIds = getBusyVehicleIds();
        return vehicleRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(v -> toResponse(v, busyVehicleIds.contains(v.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public VehicleResponse get(UUID id) {
        Vehicle vehicle = getVehicle(id);
        return toResponse(vehicle, getBusyVehicleIds().contains(vehicle.getId()));
    }

    @Transactional
    public VehicleResponse create(UserPrincipal principal, CreateVehicleRequest request) {
        String normalizedPlate = normalizePlate(request.getPlate());
        if (vehicleRepository.existsByPlateIgnoreCase(normalizedPlate)) {
            throw AppException.conflict("Vehicle plate already exists");
        }

        Vehicle vehicle = Vehicle.builder()
                .name(buildDisplayName(request.getMake(), request.getModel(), request.getManufactureYear()))
                .make(request.getMake().trim())
                .model(request.getModel().trim())
                .manufactureYear(request.getManufactureYear())
                .color(trimOrNull(request.getColor()))
                .vin(normalizeVin(request.getVin()))
                .fuelType(trimOrNull(request.getFuelType()))
                .payloadKg(request.getPayloadKg())
                .volumeM3(request.getVolumeM3())
                .mileageKm(request.getMileageKm())
                .plate(normalizedPlate)
                .type(request.getType())
                .active(true)
                .build();

        vehicle = vehicleRepository.save(vehicle);

        if (StringUtils.hasText(request.getImageBase64())) {
            String objectPath = "vehicles/" + vehicle.getId() + "/primary-" + System.currentTimeMillis() + ".png";
            vehicle.setImageUrl(minioStorageService.uploadBase64(request.getImageBase64(), objectPath));
            vehicle = vehicleRepository.save(vehicle);
        }

        auditLogService.logAction(principal, "CREATE_VEHICLE", "VEHICLE", vehicle.getId().toString(),
                java.util.Map.of("vehicule", vehicle.getName(), "plaque", vehicle.getPlate(), "action", "Creation de vehicule"));

        return toResponse(vehicle, getBusyVehicleIds().contains(vehicle.getId()));
    }

    @Transactional
    public VehicleResponse update(UUID id, UserPrincipal principal, UpdateVehicleRequest request) {
        Vehicle vehicle = getVehicle(id);

        if (StringUtils.hasText(request.getMake())) {
            vehicle.setMake(request.getMake().trim());
        }

        if (StringUtils.hasText(request.getModel())) {
            vehicle.setModel(request.getModel().trim());
        }

        if (request.getManufactureYear() != null) {
            vehicle.setManufactureYear(request.getManufactureYear());
        }

        if (request.getColor() != null) {
            vehicle.setColor(trimOrNull(request.getColor()));
        }

        if (request.getVin() != null) {
            vehicle.setVin(normalizeVin(request.getVin()));
        }

        if (request.getFuelType() != null) {
            vehicle.setFuelType(trimOrNull(request.getFuelType()));
        }

        if (request.getPayloadKg() != null) {
            vehicle.setPayloadKg(request.getPayloadKg());
        }

        if (request.getVolumeM3() != null) {
            vehicle.setVolumeM3(request.getVolumeM3());
        }

        if (request.getMileageKm() != null) {
            vehicle.setMileageKm(request.getMileageKm());
        }

        if (StringUtils.hasText(request.getPlate())) {
            String normalizedPlate = normalizePlate(request.getPlate());
            if (!normalizedPlate.equalsIgnoreCase(vehicle.getPlate()) && vehicleRepository.existsByPlateIgnoreCase(normalizedPlate)) {
                throw AppException.conflict("Vehicle plate already exists");
            }
            vehicle.setPlate(normalizedPlate);
        }

        if (request.getType() != null) {
            vehicle.setType(request.getType());
        }

        if (request.getActive() != null) {
            vehicle.setActive(request.getActive());
        }

        if (request.getImageBase64() != null) {
            if (!StringUtils.hasText(request.getImageBase64())) {
                if (StringUtils.hasText(vehicle.getImageUrl())) {
                    minioStorageService.deleteFile(vehicle.getImageUrl());
                    vehicle.setImageUrl(null);
                }
            } else {
                if (StringUtils.hasText(vehicle.getImageUrl())) {
                    minioStorageService.deleteFile(vehicle.getImageUrl());
                }
                String objectPath = "vehicles/" + vehicle.getId() + "/primary-" + System.currentTimeMillis() + ".png";
                vehicle.setImageUrl(minioStorageService.uploadBase64(request.getImageBase64(), objectPath));
            }
        }

        vehicle.setName(buildDisplayName(vehicle.getMake(), vehicle.getModel(), vehicle.getManufactureYear()));

        Vehicle saved = vehicleRepository.save(vehicle);
        auditLogService.logAction(principal, "UPDATE_VEHICLE", "VEHICLE", saved.getId().toString(),
                java.util.Map.of("vehicule", saved.getName(), "plaque", saved.getPlate(), "action", "Mise a jour de vehicule"));
        return toResponse(saved, getBusyVehicleIds().contains(saved.getId()));
    }

    @Transactional
    public void delete(UUID id, UserPrincipal principal) {
        Vehicle vehicle = getVehicle(id);
        auditLogService.logAction(principal, "DELETE_VEHICLE", "VEHICLE", id.toString(),
                java.util.Map.of("vehicule", vehicle.getName(), "plaque", vehicle.getPlate(), "action", "Suppression de vehicule"));
        if (StringUtils.hasText(vehicle.getImageUrl())) {
            minioStorageService.deleteFile(vehicle.getImageUrl());
        }
        vehicleRepository.delete(vehicle);
    }

    @Transactional
    public VehicleResponse assign(UUID id, UserPrincipal principal, AssignVehicleRequest request) {
        Vehicle vehicle = getVehicle(id);
        String driverInfo = request.getDriverId() != null ? request.getDriverId().toString() : "unassigned";
        vehicle.setDriverId(request.getDriverId());
        Vehicle saved = vehicleRepository.save(vehicle);
        auditLogService.logAction(principal, "ASSIGN_VEHICLE", "VEHICLE", saved.getId().toString(),
                java.util.Map.of("vehicule", saved.getName(), "plaque", saved.getPlate(), "chauffeur", driverInfo, "action", "Affectation de vehicule"));
        return toResponse(saved, getBusyVehicleIds().contains(saved.getId()));
    }

    private Set<UUID> getBusyVehicleIds() {
        return routeRepository.findByStatusIn(ACTIVE_ROUTE_STATUSES).stream()
                .map(Route::getVehicleId)
                .filter(v -> v != null)
                .collect(Collectors.toSet());
    }

    private Vehicle getVehicle(UUID id) {
        return vehicleRepository.findById(id).orElseThrow(() -> AppException.notFound("Vehicle not found"));
    }

    private static String normalizePlate(String plate) {
        return plate == null ? null : plate.trim().toUpperCase();
    }

    private static String normalizeVin(String vin) {
        return vin == null ? null : vin.trim().toUpperCase();
    }

    private static String trimOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String buildDisplayName(String make, String model, Integer year) {
        String safeMake = StringUtils.hasText(make) ? make.trim() : "Vehicle";
        String safeModel = StringUtils.hasText(model) ? model.trim() : "Model";
        if (year == null) {
            return safeMake + " " + safeModel;
        }
        return year + " " + safeMake + " " + safeModel;
    }

    private VehicleResponse toResponse(Vehicle vehicle, boolean assigned) {
        return VehicleResponse.builder()
                .id(vehicle.getId())
                .name(vehicle.getName())
                .make(vehicle.getMake())
                .model(vehicle.getModel())
                .manufactureYear(vehicle.getManufactureYear())
                .color(vehicle.getColor())
                .vin(vehicle.getVin())
                .fuelType(vehicle.getFuelType())
                .payloadKg(vehicle.getPayloadKg())
                .volumeM3(vehicle.getVolumeM3())
                .mileageKm(vehicle.getMileageKm())
                .plate(vehicle.getPlate())
                .type(vehicle.getType())
                .imageUrl(vehicle.getImageUrl())
                .driverId(vehicle.getDriverId())
                .assigned(assigned)
                .active(vehicle.getActive())
                .createdAt(vehicle.getCreatedAt())
                .build();
    }
}
