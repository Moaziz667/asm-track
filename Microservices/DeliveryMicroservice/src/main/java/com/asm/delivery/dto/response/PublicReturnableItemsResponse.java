package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a client can still return for a delivery, plus the current open-return state. Drives the public
 * return form on the tracking page (item checkboxes bounded by {@code returnableQty}).
 */
@Data
@Builder
public class PublicReturnableItemsResponse {

    private String deliveryStatus;
    private boolean returnable;      // delivery is DELIVERED / PARTIALLY_DELIVERED
    private boolean hasOpenReturn;
    private UUID openReturnId;
    private String openReturnStatus;
    private List<ReturnableItem> items;

    @Data
    @Builder
    public static class ReturnableItem {
        private String sku;
        private String name;
        private Integer deliveredQty;
        private Integer returnableQty;
        private BigDecimal unitPrice;
    }
}
