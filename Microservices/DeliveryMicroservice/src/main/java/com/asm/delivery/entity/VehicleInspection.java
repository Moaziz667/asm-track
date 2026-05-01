package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "vehicle_inspections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleInspection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "odometer_reading")
    private Integer odometerReading;

    @Column(name = "fuel_level")
    private Integer fuelLevel; // Percentage 0-100

    @Enumerated(EnumType.STRING)
    @Column(name = "tires_status", length = 20)
    private InspectionStatus tiresStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "brakes_status", length = 20)
    private InspectionStatus brakesStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "lights_status", length = 20)
    private InspectionStatus lightsStatus;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "inspected_at", nullable = false)
    private LocalDateTime inspectedAt;

    public enum InspectionStatus {
        PASS, FAIL, WARNING
    }

    @PrePersist
    void prePersist() {
        if (inspectedAt == null) {
            inspectedAt = LocalDateTime.now();
        }
    }
}
