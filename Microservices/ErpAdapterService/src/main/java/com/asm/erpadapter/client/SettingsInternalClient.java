package com.asm.erpadapter.client;

import com.asm.erpadapter.dto.SystemSettingsDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Declarative client for AppBackend's internal ERP-settings endpoint. Service auth is applied
 * globally by {@code ServiceClientConfig}'s Feign interceptor (SERVICE-role client_credentials token).
 */
@FeignClient(name = "app-backend-settings", url = "${APP_BACKEND_URL:http://app-backend:8080}")
public interface SettingsInternalClient {

    @GetMapping("/api/settings/internal/erp")
    SystemSettingsDto getErpSettings();
}
