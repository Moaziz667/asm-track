package com.asm.driver.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class RegisterRequest {
    @NotBlank private String name;
    @NotBlank private String phone;
    @NotBlank private String password;
}
