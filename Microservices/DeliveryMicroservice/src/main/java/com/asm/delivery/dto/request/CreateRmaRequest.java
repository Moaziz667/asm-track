package com.asm.delivery.dto.request;

import com.asm.delivery.entity.RmaItemCondition;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreateRmaRequest {

    @NotNull
    private UUID deliveryId;

    private String reason;

    @NotEmpty
    private List<Item> items;

    @Data
    public static class Item {
        private String sku;
        private String name;
        @NotNull
        @Min(1)
        private Integer quantity;
        private BigDecimal unitPrice;
        private RmaItemCondition condition;
        private String reason;
    }
}
