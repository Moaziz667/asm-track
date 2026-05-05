package com.asm.delivery.erp;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateErpMapOrderRequest {

    @NotBlank
    @Size(max = 120)
    private String customerName;

    @NotBlank
    @Size(max = 40)
    private String customerPhone;

    @NotBlank
    @Size(max = 200)
    private String street;

    @Size(max = 120)
    private String street2;

    @NotBlank
    @Size(max = 80)
    private String city;

    @Size(max = 20)
    private String zip;

    @NotBlank
    @Size(max = 120)
    private String itemName;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal unitPrice;

    @NotNull
    @DecimalMin("1")
    private Integer quantity;

    private Double latitude;

    private Double longitude;

    @Size(max = 500)
    private String note;
}
