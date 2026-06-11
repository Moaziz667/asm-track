package com.asm.erpadapter.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Proof-of-delivery payload pushed to the ERP after a successful delivery.
 *
 * <p>Photos are normally carried as <b>MinIO URLs</b> (the delivery platform stores them and the
 * adapter fetches the bytes over plain HTTP) — this keeps large binaries out of the outbox table and
 * the RabbitMQ frames. The legacy base64 fields are kept for backward compatibility; when a URL is
 * present it wins.
 */
@Data
@Builder
public class ErpPodDTO {
    private String recipientName;
    private String comment;
    private String deliveredAt;   // ISO-8601 timestamp
    private Double lat;
    private Double lng;

    // Preferred: MinIO object URLs (adapter fetches the bytes via HTTP GET).
    private String bonLivraisonPhotoUrl; // signed delivery note photo
    private String packagePhotoUrl;      // handover/package photo

    // Legacy fallback: inline base64 (PNG). Used only when the URL is absent.
    private String blPhotoBase64;
    private String packagePhotoBase64;
}
