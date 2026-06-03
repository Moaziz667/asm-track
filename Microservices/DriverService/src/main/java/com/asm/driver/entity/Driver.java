package com.asm.driver.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "drivers")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Driver {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(nullable = false)
    private String passwordHash;

    @Column(precision = 10, scale = 7)
    private BigDecimal currentLat;

    @Column(precision = 10, scale = 7)
    private BigDecimal currentLng;

    private LocalDateTime lastLocationAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    @Builder.Default
    private DriverAccountStatus accountStatus = DriverAccountStatus.PENDING_SETUP;

    @Enumerated(EnumType.STRING)
    @Column(name = "online_status", length = 20, nullable = false)
    @Builder.Default
    private DriverOnlineStatus onlineStatus = DriverOnlineStatus.OFFLINE;



    @Column(length = 255)
    private String email;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(length = 500)
    private String fcmToken;

    @Column(name = "suspended_reason", length = 500)
    private String suspendedReason;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
