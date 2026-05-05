package com.asm.driver.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class IncrementStatRequest {
    @NotBlank private String field; // "delivered", "failed", "cancelled"
}
