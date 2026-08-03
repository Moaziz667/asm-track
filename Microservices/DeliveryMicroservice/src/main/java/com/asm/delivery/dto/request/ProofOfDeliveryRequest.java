package com.asm.delivery.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProofOfDeliveryRequest {
    // ADR-033 — optional: a return collection has no delivery note (bon de livraison). Required for a
    // FORWARD delivery, enforced in the service where the leg kind is known.
    private String bonLivraisonPhotoBase64; // signed receipt photo

    @NotBlank
    private String packagePhotoBase64;       // package handover photo

    /**
     * Who took delivery of the parcel. Shown on the Odoo chatter note ("Reçu par") and kept on the POD
     * row as part of the delivery evidence: the photo shows the parcel, this names the person who
     * signed for it, which is what a delivery dispute actually turns on. The ERP payload has always
     * carried the field — nothing ever populated it.
     */
    @Size(max = 150)
    private String recipientName;

    private String comment;
    private BigDecimal lat;
    private BigDecimal lng;
    @JsonAlias("isPartial")
    private boolean partial;
    private List<PartialDeliveryItem> itemsDone;

    /**
     * What the driver took, when the order carries a collection instruction.
     *
     * <p>Rides on the POD rather than on its own call so the two cannot come apart: the money changes
     * hands at the same doorstep, in the same moment, as the parcel. A separate endpoint would make it
     * possible to complete a delivery and only afterwards — or never — say what was collected, and the
     * gap between the two is exactly where cash goes missing.
     *
     * <p>Null when the order is not COD; ignored (with a log) if sent anyway.
     */
    private CashCollectionEntry cash;

    /** Driver-reported settlement at the door. */
    @Data
    public static class CashCollectionEntry {
        /** What was taken. Zero or null means nothing — then {@link #reason} is required. */
        private BigDecimal amountCollected;
        /** CASH, CHEQUE or NONE. */
        private String method;
        private String chequeNumber;
        private String chequeBank;
        private String chequeDate;   // ISO yyyy-MM-dd
        /** Failure-reason catalog code; required when less than the expected amount was taken. */
        private String reason;
    }
}
