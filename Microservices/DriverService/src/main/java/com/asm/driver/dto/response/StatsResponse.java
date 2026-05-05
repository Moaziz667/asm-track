package com.asm.driver.dto.response;
import lombok.Builder;
import lombok.Data;
@Data @Builder
public class StatsResponse {
    private Integer totalDeliveries;
    private Integer delivered;
    private Integer failed;
    private Integer cancelled;
}
