package com.asm.erpadapter.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.UUID;

/**
 * Lists provisioned tenants from AppBackend (which owns the schema catalog). The ERP adapter has no
 * database of its own, so it can't enumerate {@code company_<hex>} schemas locally the way the other
 * services do — it asks AppBackend instead. Service auth (SERVICE-role token) is applied globally by
 * {@code ServiceClientConfig}'s Feign interceptor.
 */
@FeignClient(name = "app-backend-tenants", url = "${APP_BACKEND_URL:http://app-backend:8080}")
public interface TenantListClient {

    @GetMapping("/internal/tenants")
    List<UUID> listTenants();
}
