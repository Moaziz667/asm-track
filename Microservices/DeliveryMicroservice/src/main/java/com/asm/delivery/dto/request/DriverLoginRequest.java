package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DriverLoginRequest {
    @NotBlank
    private String phone;

    @NotBlank
    private String password;
}
