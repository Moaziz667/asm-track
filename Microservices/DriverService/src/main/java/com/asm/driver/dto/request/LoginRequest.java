package com.asm.driver.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class LoginRequest {
    @NotBlank private String phone;
    @NotBlank private String password;
}
