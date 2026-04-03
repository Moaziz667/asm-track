package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminExceptionEscalateRequest {
    @NotBlank
    @Size(max = 500)
    private String note;

    @Pattern(regexp = "L1|L2|L3", message = "level must be one of L1, L2, or L3")
    private String level;
}
