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
    private BigDecimal currentLat;
    private BigDecimal currentLng;
    private LocalDateTime lastLocationAt;
    private LocalDateTime createdAt;
    private String fcmToken;
    private Boolean active;
    private String onlineStatus;
}
