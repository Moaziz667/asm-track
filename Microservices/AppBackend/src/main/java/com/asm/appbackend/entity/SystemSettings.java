package com.asm.appbackend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSettings {

    /**
     * Since this is a single-tenant architecture, there will only ever be one row in this table.
     * We hardcode the ID to a specific string like "SINGLETON" or just use a standard ID and always fetch the first row.
     */
    @Id
    @Column(length = 50)
    @Builder.Default
    private String id = "SINGLETON";

    /**
     * e.g., "ODOO", "DUX", or "NONE"
     */
    @Column(name = "active_erp_provider", length = 50)
    private String activeErpProvider;

    /**
     * AES-256 Encrypted JSON string containing the specific credentials for the active provider.
     */
    @Column(name = "erp_configuration", columnDefinition = "TEXT")
    private String erpConfiguration;

    // ── Connection lifecycle ──────────────────────────────────────────────────
    // "Configured" (creds saved) is NOT "connected" (creds verified). These fields make the
    // connection state persistent and visible, so a source with false creds can't masquerade
    // as active on the Import page.
    // NOT_CONFIGURED | CONFIGURED | CONNECTED | ERROR
    @Column(name = "connection_status", length = 20, nullable = false)
    @Builder.Default
    private String connectionStatus = "NOT_CONFIGURED";

    /** Last time a connection test was run (success or failure). */
    @Column(name = "last_tested_at")
    private LocalDateTime lastTestedAt;

    /** Last time a test actually succeeded — i.e. the creds were last known-good. */
    @Column(name = "last_connected_at")
    private LocalDateTime lastConnectedAt;

    /** Human-readable reason for the most recent ERROR (null when CONNECTED). */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** uid returned by the last successful Odoo authenticate, for display/debug. */
    @Column(name = "last_test_uid", length = 50)
    private String lastTestUid;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
