package com.asm.driver.dto.response;
import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data @Builder
public class HistoryResponse {
    private List<HistoryItem> items;
    @Data @Builder
    public static class HistoryItem {
        private String deliveryId;
        private String status;
    }
}
