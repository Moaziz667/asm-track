package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminExceptionReplanRequest {
    @NotBlank
    @Size(max = 500)
    private String note;

    /**
     * New scheduled date for the replanned delivery. When set, it overrides the stale ERP
     * scheduled date so SLAs measure against the new commitment. Optional (kept for back-compat).
     */
    private LocalDateTime scheduledAt;
}
