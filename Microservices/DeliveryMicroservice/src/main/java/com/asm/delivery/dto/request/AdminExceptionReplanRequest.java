package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminExceptionReplanRequest {
    @NotBlank
    @Size(max = 500)
    private String note;
}
