package com.asm.delivery.dto.request;

import com.asm.delivery.entity.ReportType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReportRequest {
    @NotNull
    private ReportType reportType;

    private String description;
}
