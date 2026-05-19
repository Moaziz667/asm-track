package com.asm.appbackend.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CreateAdminUserRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank @Pattern(regexp = "SUPER_ADMIN|ADMIN|DISPATCHER|MANAGER") String role,
        UUID companyId   // null = super-admin
) {}
