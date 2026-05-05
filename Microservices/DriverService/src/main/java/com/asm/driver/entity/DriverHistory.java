package com.asm.driver.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "driver_history")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DriverHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID driverId;

    @Column(nullable = false)
    private String deliveryId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
