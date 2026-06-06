package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a partial delivery sync.
 * Contains the picking ID that was validated and the new backorder picking ID
 * for the remaining items.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpPartialDeliveryResultDTO {
    private boolean success;
    private Integer pickingId;
    private Integer backorderPickingId;
    /** Delivery-note (BL) number of the created backorder picking, e.g. "WH/OUT/00013". */
    private String backorderBlNumber;
}
