package com.asm.delivery.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProofOfDeliveryRequest {
    private String photoBase64;
    @NotBlank
    private String signatureBase64;
    private String comment;
    private BigDecimal lat;
    private BigDecimal lng;
    @JsonAlias("isPartial")
    private boolean partial;
    private List<PartialDeliveryItem> itemsDone;
}
