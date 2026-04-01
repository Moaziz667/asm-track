package com.asm.driver.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
@Data
public class AvailabilityRequest {
    @NotNull private Boolean available;
}
