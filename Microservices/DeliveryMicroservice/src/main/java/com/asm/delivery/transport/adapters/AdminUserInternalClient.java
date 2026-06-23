package com.asm.delivery.transport.adapters;

import com.asm.delivery.transport.AdminUserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Declarative client for AppBackend's internal admin/dispatcher identity lookup. Mirrors
 * {@link DriverInternalClient}: service auth + caller-identity headers are applied globally by
 * {@code ServiceClientConfig}'s Feign interceptor. Used to turn the admin UUID stored in
 * delivery status history into a real person's name for the activity timeline.
 */
@FeignClient(name = "appbackend-internal", url = "${appbackend.service.url:http://app-backend:8080}")
public interface AdminUserInternalClient {

    @GetMapping("/internal/admin-users/{id}")
    AdminUserDTO getById(@PathVariable("id") String id);

    @PostMapping("/internal/admin-users/by-ids")
    List<AdminUserDTO> getByIds(@RequestBody List<String> ids);
}
