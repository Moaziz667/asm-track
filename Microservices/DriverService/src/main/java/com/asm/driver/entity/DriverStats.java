package com.asm.driver.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "driver_stats")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DriverStats {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID driverId;

    @Builder.Default
    private Integer totalDeliveries = 0;

    @Builder.Default
    private Integer delivered = 0;

    @Builder.Default
    private Integer failed = 0;

    @Builder.Default
    private Integer cancelled = 0;

    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
