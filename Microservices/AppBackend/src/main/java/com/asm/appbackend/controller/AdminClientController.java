package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.ClientResponse;
import com.asm.appbackend.entity.Client;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.repository.ClientRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/clients")
@Tag(name = "Admin Clients", description = "Client management for admin dashboard")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminClientController {

    private final ClientRepository clientRepository;

    @GetMapping
    @Operation(summary = "List clients with optional search and pagination")
    public ResponseEntity<Page<ClientResponse>> listClients(
            @RequestParam(required = false) String search,
            @ParameterObject Pageable pageable) {
        throw AppException.forbidden("Client listing is disabled: clients are managed in ERP only");
    }
}
