package com.asm.appbackend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSettingsDto {
    private String activeErpProvider;
    private Object erpConfiguration; // We will use Object to accept/return arbitrary JSON maps

    // ── Connection lifecycle (read-only for the UI; never set by the client) ──────
    private String connectionStatus;     // NOT_CONFIGURED | CONFIGURED | CONNECTED | ERROR
    private LocalDateTime lastTestedAt;
    private LocalDateTime lastConnectedAt;
    private String lastError;
    private String lastTestUid;

    /** Back-compat constructor used across the controller (provider + config only). */
    public SystemSettingsDto(String activeErpProvider, Object erpConfiguration) {
        this.activeErpProvider = activeErpProvider;
        this.erpConfiguration = erpConfiguration;
    }
}
