package com.asm.appbackend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DEAD CODE: This entity belongs to the legacy phone-based OTP verification for Clients.
 * Since Client authentication is disabled, this is no longer used.
 */
@Entity
@Table(name = "client_otp")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Deprecated
public class ClientOtp {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "code", nullable = false, length = 6)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
