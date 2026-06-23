package com.asm.appbackend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "admin_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(unique = true, nullable = false, length = 100)
    private String email;


    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * False whenever this row may differ from its Keycloak mirror (just created/updated). The
     * reconciler processes dirty rows first and flips it true once Keycloak is confirmed in sync,
     * so the fast path doesn't re-scan every user every tick.
     */
    @Column(name = "kc_synced", nullable = false)
    @Builder.Default
    private boolean kcSynced = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
