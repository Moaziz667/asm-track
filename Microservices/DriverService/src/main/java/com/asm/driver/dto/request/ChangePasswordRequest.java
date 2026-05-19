package com.asm.driver.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank
    private String oldPassword;

    @NotBlank
    @Size(min = 8, message = "Le nouveau mot de passe doit contenir au moins 8 caractères")
    @jakarta.validation.constraints.Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
        message = "Le mot de passe doit contenir au moins une lettre et un chiffre"
    )
    private String newPassword;

    @NotBlank
    private String confirmPassword;
}
