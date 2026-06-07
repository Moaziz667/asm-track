package com.asm.erpadapter.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Proof-of-delivery payload pushed to the ERP after a successful delivery.
 * Photos are carried as base64 (PNG) so the adapter can create ir.attachment
 * records without needing access to the delivery platform's object storage.
 */
@Data
@Builder
public class ErpPodDTO {
    private String recipientName;
    private String comment;
    private String deliveredAt;   // ISO-8601 timestamp
    private Double lat;
    private Double lng;
    private String blPhotoBase64;       // signed delivery note photo
    private String packagePhotoBase64;  // handover/package photo
}
