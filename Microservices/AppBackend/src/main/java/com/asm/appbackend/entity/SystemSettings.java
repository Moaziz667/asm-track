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

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
