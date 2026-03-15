package com.asm.delivery.dto.request;

import com.asm.delivery.entity.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateOrderRequest {

    @NotBlank
    private String dropoffAddress;

    private String dropoffCity;
    private String dropoffPostalCode;

    private BigDecimal dropoffLat;
    private BigDecimal dropoffLng;

    private String deliveryInstructions;

    @NotNull
    @DecimalMin("0.000")
    private BigDecimal totalAmount;

    @NotBlank
    @Pattern(regexp = "COD|PREPAID", message = "paymentType must be COD or PREPAID")
    private String paymentType;

    @NotNull
    @DecimalMin("0.000")
    private BigDecimal amountToCollect;

    @NotEmpty
    @Valid
    private List<OrderItem> items;

    private String scheduledAt;  // ISO-8601 string, nullable

    @Pattern(regexp = "NORMAL|HIGH", message = "priority must be NORMAL or HIGH")
    private String priority;
}
