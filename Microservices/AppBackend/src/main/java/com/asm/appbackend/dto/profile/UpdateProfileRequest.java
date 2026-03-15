package com.asm.appbackend.dto.profile;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be <= 100 chars")
        String name,
        @Email(message = "email must be valid")
        @Size(max = 100, message = "email must be <= 100 chars")
        String email,
        @Size(max = 255, message = "address must be <= 255 chars")
        String address
) {
}
