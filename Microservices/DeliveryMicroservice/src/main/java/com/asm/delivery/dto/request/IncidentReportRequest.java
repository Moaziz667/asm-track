package com.asm.delivery.dto.request;

import com.asm.delivery.entity.ReportType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class IncidentReportRequest {
    private UUID deliveryId; // Optional

    @NotNull
    private ReportType reportType;

    @NotNull
    private String description;

    private Double lat;
    private Double lng;

    private List<String> photosBase64;
}
