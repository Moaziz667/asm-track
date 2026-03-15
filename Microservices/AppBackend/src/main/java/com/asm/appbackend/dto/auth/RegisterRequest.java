package com.asm.appbackend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be <= 100 chars")
        String name,
        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^[+]?[0-9]{8,15}$", message = "phone must be numeric and 8-15 digits")
        String phone,
        @NotBlank(message = "password is required")
        @Size(min = 6, max = 100, message = "password must be 6-100 chars")
        String password
) {
}
