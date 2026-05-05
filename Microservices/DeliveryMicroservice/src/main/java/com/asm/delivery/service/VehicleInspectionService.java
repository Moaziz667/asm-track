package com.asm.delivery.service;

import com.asm.delivery.dto.request.VehicleInspectionRequest;
import com.asm.delivery.dto.response.VehicleInspectionResponse;
import com.asm.delivery.entity.VehicleInspection;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.VehicleInspectionRepository;
import com.asm.delivery.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleInspectionService {

    private final VehicleInspectionRepository inspectionRepo;
    private final VehicleRepository vehicleRepo;

    @Transactional
    public VehicleInspectionResponse submitInspection(UUID driverId, VehicleInspectionRequest request) {
        // Validate vehicle exists
        var vehicle = vehicleRepo.findById(request.getVehicleId())
                .orElseThrow(() -> AppException.notFound("Vehicle not found"));

        // Validate driver owns/is assigned to vehicle (optional depending on business rules)
        // For PFE, we'll assume the driver submits for the vehicle they are using.

        VehicleInspection inspection = VehicleInspection.builder()
                .vehicleId(request.getVehicleId())
                .driverId(driverId)
                .odometerReading(request.getOdometerReading())
                .fuelLevel(request.getFuelLevel())
                .tiresStatus(request.getTiresStatus())
                .brakesStatus(request.getBrakesStatus())
                .lightsStatus(request.getLightsStatus())
                .notes(request.getNotes())
                .inspectedAt(LocalDateTime.now())
                .build();

        inspection = inspectionRepo.save(inspection);
        
        // Update vehicle mileage if provided
        if (request.getOdometerReading() != null) {
            vehicle.setMileageKm(request.getOdometerReading());
            vehicleRepo.save(vehicle);
        }

        log.info("INSPECTION_SUBMITTED driverId={} vehicleId={} inspectionId={}", 
                driverId, request.getVehicleId(), inspection.getId());

        return mapToResponse(inspection);
    }

    @Transactional(readOnly = true)
    public boolean hasValidRecentInspection(UUID vehicleId, UUID driverId) {
        // A "Valid" inspection is one done in the last 12 hours
        LocalDateTime threshold = LocalDateTime.now().minusHours(12);
        return inspectionRepo.existsByVehicleIdAndDriverIdAndInspectedAtAfter(vehicleId, driverId, threshold);
    }

    private VehicleInspectionResponse mapToResponse(VehicleInspection entity) {
        return VehicleInspectionResponse.builder()
                .id(entity.getId())
                .vehicleId(entity.getVehicleId())
                .driverId(entity.getDriverId())
                .odometerReading(entity.getOdometerReading())
                .fuelLevel(entity.getFuelLevel())
                .tiresStatus(entity.getTiresStatus())
                .brakesStatus(entity.getBrakesStatus())
                .lightsStatus(entity.getLightsStatus())
                .notes(entity.getNotes())
                .inspectedAt(entity.getInspectedAt())
                .build();
    }
}
