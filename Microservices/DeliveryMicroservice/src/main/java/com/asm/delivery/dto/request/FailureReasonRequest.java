package com.asm.delivery.dto.request;

import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.FailureContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

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

    /** Where the motif is offered (full failure / per-item). Defaults to FAILURE when omitted. */
    private Set<FailureContext> appliesTo;

    private Boolean active;

    private Integer sortOrder;
}
