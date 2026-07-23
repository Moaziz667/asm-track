package com.asm.delivery.transport.adapters;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * Declarative client for AppBackend's lightweight ERP-provider-only endpoint.
 * Returns just the provider key (e.g. "odoo", "erpnext") — no secrets.
 * Service auth + X-Company-Id headers are applied globally by {@code ServiceClientConfig}.
 */
@FeignClient(name = "appbackend-settings", url = "${appbackend.service.url:http://app-backend:8080}")
public interface AppBackendSettingsClient {

    @GetMapping("/api/v1/settings/internal/erp/provider")
    Map<String, String> getErpProvider();
}
