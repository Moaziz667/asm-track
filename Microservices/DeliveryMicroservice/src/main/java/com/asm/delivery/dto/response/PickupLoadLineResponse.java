package com.asm.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One line to physically collect at a PICKUP stop.
 *
 * <p>Flat rather than nested per delivery: a loading bay is walked line by line, and the order
 * reference travels on each line so a driver reading the third row still knows whose it is.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A line to load at a pickup stop")
public class PickupLoadLineResponse {

    @Schema(description = "Delivery this line belongs to")
    private UUID deliveryId;

    @Schema(description = "ERP order reference", example = "SAL-ORD-2026-00127")
    private String orderRef;

    @Schema(description = "Client the line is destined for")
    private String clientName;

    @Schema(description = "Item code as the ERP knows it", example = "BOI-EAU-006")
    private String sku;

    @Schema(description = "Item label", example = "Eau minerale 1.5L")
    private String name;

    @Schema(description = "Quantity to load")
    private Integer quantity;
}
