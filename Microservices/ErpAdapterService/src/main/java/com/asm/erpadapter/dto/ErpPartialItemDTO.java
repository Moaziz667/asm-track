package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpPartialItemDTO {
    private String referenceKey;

    /** Human-readable item name for display in notes. */
    private String itemName;

    private Integer quantityDone;

    /** Delivery outcome: DELIVERED, REFUSED, or DAMAGED. */
    private String outcome;

    /** Reason code when outcome is REFUSED or DAMAGED. */
    private String reason;

    /** Human label for {@link #reason}, resolved by the platform from its catalog. */
    private String reasonLabel;

    /** Optional driver comment for this item. */
    private String comment;
}
