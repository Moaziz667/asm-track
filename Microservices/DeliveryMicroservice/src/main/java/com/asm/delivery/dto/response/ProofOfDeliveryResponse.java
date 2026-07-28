package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProofOfDeliveryResponse {
    private UUID id;
    private UUID deliveryId;
    private String photoBase64;
    private String signatureBase64;
    private String signatureUrl;
    private String photoUrl;
    private String bonLivraisonPhotoUrl; // signed receipt (bon de livraison) photo
    private String comment;
    private String recipientName;
    private LocalDateTime collectedAt;
    private BigDecimal lat;
    private BigDecimal lng;
}
