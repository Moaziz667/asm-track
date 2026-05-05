package com.asm.driver.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class LocationRequest {
    @NotNull private BigDecimal lat;
    @NotNull private BigDecimal lng;
}
