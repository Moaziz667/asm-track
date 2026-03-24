package com.asm.delivery.dto.request;

import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.entity.OrderPriority;
import com.asm.delivery.entity.PaymentType;
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

    @NotNull
    private PaymentType paymentType;

    @NotNull
    @DecimalMin("0.000")
    private BigDecimal amountToCollect;

    @NotEmpty
    @Valid
    private List<OrderItem> items;

    private String scheduledAt;  // ISO-8601 string, nullable

    private OrderPriority priority;
}
