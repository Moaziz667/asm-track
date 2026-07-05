package com.asm.delivery.dto.request;

import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.ReasonScope;
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

    /** Where the motif is usable: DELIVERY / ITEM / BOTH. Defaults to DELIVERY when omitted.
     *  ITEM/BOTH is only valid when {@link #category} is a per-item disposition (REFUSED/DAMAGED/MISSING). */
    private ReasonScope scope;

    private Boolean active;

    private Integer sortOrder;
}
