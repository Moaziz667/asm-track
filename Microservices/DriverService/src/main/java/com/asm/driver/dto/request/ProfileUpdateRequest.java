package com.asm.driver.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class ProfileUpdateRequest {
    @NotBlank private String name;
}
