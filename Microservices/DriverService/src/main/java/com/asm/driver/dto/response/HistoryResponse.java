package com.asm.driver.dto.response;
import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data @Builder
public class HistoryResponse {
    private List<HistoryItem> items;
    /** True when more pages exist — drives the mobile infinite-scroll loader. */
    private boolean hasNext;
    @Data @Builder
    public static class HistoryItem {
        private String deliveryId;
        private String status;
        /** ISO timestamp of the record — drives the app's history date display/filter. */
        private String createdAt;
    }
}
