package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PodRequest {

    @NotBlank
    private String signatureBase64;

    private String photoBase64;

    private String comment;

    private BigDecimal lat;

    private BigDecimal lng;
}
