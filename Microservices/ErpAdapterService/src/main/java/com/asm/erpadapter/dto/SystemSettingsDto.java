package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSettingsDto {
    private String activeErpProvider;
    private Map<String, Object> erpConfiguration;
    // Persisted connection lifecycle from AppBackend: NOT_CONFIGURED | CONFIGURED | CONNECTED | ERROR.
    // The adapter must not pull orders with credentials that aren't verified CONNECTED.
    private String connectionStatus;
}
