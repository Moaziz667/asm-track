package com.asm.delivery.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartialDeliveryItem {
    private String sku;
    @JsonAlias({"productId"})
    private String productId;
    @JsonAlias({"itemId", "id"})
    private String itemId;
    private Integer quantityDone;

    public PartialDeliveryItem(String sku, Integer quantityDone) {
        this.sku = sku;
        this.quantityDone = quantityDone;
    }

    public String referenceKey() {
        if (sku != null && !sku.isBlank()) {
            return sku.trim();
        }
        if (productId != null && !productId.isBlank()) {
            return productId.trim();
        }
        if (itemId != null && !itemId.isBlank()) {
            return itemId.trim();
        }
        return null;
    }
}
