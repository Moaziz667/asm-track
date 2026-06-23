package com.asm.driver.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Synchronous IAM calls to AppBackend (the sole Keycloak owner) for operations that can't be
 * eventual — setting a driver's password at onboarding + enabling the account so they can log in
 * immediately. Everything else goes through the IAM outbox (async). Service auth + caller identity
 * are applied by {@code ServiceClientConfig}'s Feign interceptor.
 */
@FeignClient(name = "appbackend-iam", url = "${appbackend.service.url:http://app-backend:8080}")
public interface AppBackendIamClient {

    @PostMapping("/internal/iam/{appUserId}/password")
    void setPassword(@PathVariable("appUserId") String appUserId, @RequestBody Map<String, String> body);

    @PostMapping("/internal/iam/{appUserId}/enabled")
    void setEnabled(@PathVariable("appUserId") String appUserId, @RequestBody Map<String, Boolean> body);
}
