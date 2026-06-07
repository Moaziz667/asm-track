package com.asm.delivery.dto.request;

import com.asm.delivery.entity.FailureCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FailureReasonRequest {

    /** Optional on create (auto-derived from label when blank); ignored on update. */
    @Size(max = 60)
    private String code;

    @NotBlank
    @Size(max = 160)
    private String label;

    /** Analytics category the reason rolls up to. */
    @NotNull
    private FailureCode category;

    private Boolean active;

    private Integer sortOrder;
}
