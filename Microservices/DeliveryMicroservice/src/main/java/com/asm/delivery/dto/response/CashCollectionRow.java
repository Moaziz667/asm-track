package com.asm.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One collection inside a handover, named the way the depot names it.
 *
 * <p>The endpoint used to return the {@code CashCollection} entity as-is, which identifies a stop by
 * two UUIDs. Nobody at a counter knows a delivery by its UUID: the argument at the desk is about a
 * delivery note — "SFX/OUT/00306, il manque 200 dinars" — and until the number was on the screen the
 * cash desk could not answer it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single doorstep collection making up a driver's handover")
public class CashCollectionRow {

    private UUID id;

    @Schema(description = "Followed through to the delivery details screen")
    private UUID deliveryId;

    @Schema(description = "Official ERP delivery-note number", example = "SFX/OUT/00306")
    private String blNumber;

    @Schema(description = "Who the money was collected from")
    private String clientName;

    @Schema(description = "What the ERP said to collect, frozen at the doorstep")
    private BigDecimal amountExpected;

    @Schema(description = "What the driver reported taking")
    private BigDecimal amountCollected;

    @Schema(description = "CASH, CHEQUE or NONE")
    private String method;

    private String chequeNumber;
    private String chequeBank;

    @Schema(description = "COLLECTED, PARTIAL, REFUSED or PENDING")
    private String status;

    @Schema(description = "Why less than expected was taken, from the failure-reason catalog")
    private String reasonLabel;

    private LocalDateTime collectedAt;
}
