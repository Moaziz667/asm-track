package com.asm.driver.dto.response;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @Builder
public class InternalDriverResponse {
    private String id;
    private String name;
    private String phone;
    private Boolean available;
    private BigDecimal currentLat;
    private BigDecimal currentLng;
    private LocalDateTime lastLocationAt;
}
