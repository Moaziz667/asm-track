package com.asm.appbackend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResendOtpRequest(
        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^[+]?[0-9]{8,15}$", message = "phone must be numeric and 8-15 digits")
        String phone
) {
}
