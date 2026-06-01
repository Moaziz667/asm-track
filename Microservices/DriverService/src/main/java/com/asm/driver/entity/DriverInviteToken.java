package com.asm.driver.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "driver_invite_tokens",
    indexes = {
        @Index(name = "idx_invite_token_driver", columnList = "driver_id"),
        @Index(name = "idx_invite_token_expires", columnList = "expires_at")
    }
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DriverInviteToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(nullable = false, unique = true)
    private UUID token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
