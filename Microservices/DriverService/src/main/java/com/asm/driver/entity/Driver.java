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


    @Column(precision = 10, scale = 7)
    private BigDecimal currentLat;

    @Column(precision = 10, scale = 7)
    private BigDecimal currentLng;

    private LocalDateTime lastLocationAt;

    /**
     * Last time the app was seen holding its realtime connection.
     *
     * <p>Distinct from {@link #lastLocationAt}: a driver reading his round in a car park is present
     * without moving, and judging him on movement alone declared him unreachable while he was
     * looking at the screen.
     */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

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

    @Column(name = "is_registered", nullable = false)
    @Builder.Default
    private Boolean isRegistered = true;

    @Column(length = 500)
    private String fcmToken;

    @Column(name = "suspended_reason", length = 500)
    private String suspendedReason;

    // ── Profile photo (avatar) ───────────────────────────────────────────────────
    /** Public URL of the current avatar thumbnail (null = no photo → UI shows initials). */
    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    /** Monotonic version; bumped on each upload so object keys + URLs are cache-busting. */
    @Column(name = "photo_version")
    private Integer photoVersion;

    /** NONE | READY | REJECTED — lifecycle of the stored photo. */
    @Column(name = "photo_status", length = 20)
    private String photoStatus;

    @Column(name = "photo_updated_at")
    private LocalDateTime photoUpdatedAt;

    /** PHOTO_REQUIRED → COMPLETE — drives the driver-app first-login mandatory photo gate. */
    @Column(name = "onboarding_status", length = 30)
    private String onboardingStatus;

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
