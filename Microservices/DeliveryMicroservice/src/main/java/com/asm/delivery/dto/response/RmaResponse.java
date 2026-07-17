package com.asm.delivery.dto.response;

import com.asm.delivery.entity.Rma;
import com.asm.delivery.entity.RmaItem;
import com.asm.delivery.entity.RmaItemCondition;
import com.asm.delivery.entity.RmaStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RmaResponse {
    private UUID id;
    private String rmaNumber;
    private UUID deliveryId;
    private UUID orderId;
    private String erpOrderId;
    private String blNumber;
    private String clientName;
    private RmaStatus status;
    private String reason;
    private String resolutionNote;
    /** State of the ERP reverse-move sync once RESTOCKED: PENDING_SYNC → SYNCED / SYNC_FAILED. */
    private String erpSyncStatus;
    /** Last ERP reverse-move error when {@code erpSyncStatus = SYNC_FAILED}, for operator triage. */
    private String erpSyncError;
    private List<Item> items;
    /** Client-uploaded evidence photos (MinIO URLs). Populated on single-return reads; null in list views. */
    private List<String> photoUrls;
    private int totalUnits;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime receivedAt;
    private LocalDateTime restockedAt;
    private String trackingNumber;
    private String shippingCarrier;
    private LocalDateTime shippedAt;

    @Data
    @Builder
    public static class Item {
        private UUID id;
        private String sku;
        private String name;
        private Integer quantity;
        private BigDecimal unitPrice;
        private RmaItemCondition condition;
        private String reason;
    }

    public static RmaResponse from(Rma r) {
        List<Item> items = r.getItems().stream().map(RmaResponse::item).toList();
        return RmaResponse.builder()
                .id(r.getId())
                .rmaNumber(r.getRmaNumber())
                .deliveryId(r.getDeliveryId())
                .orderId(r.getOrderId())
                .erpOrderId(r.getErpOrderId())
                .blNumber(r.getBlNumber())
                .clientName(r.getClientName())
                .status(r.getStatus())
                .reason(r.getReason())
                .resolutionNote(r.getResolutionNote())
                .erpSyncStatus(r.getErpSyncStatus())
                .erpSyncError(r.getErpSyncError())
                .items(items)
                .totalUnits(items.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAt())
                .receivedAt(r.getReceivedAt())
                .restockedAt(r.getRestockedAt())
                .trackingNumber(r.getTrackingNumber())
                .shippingCarrier(r.getShippingCarrier())
                .shippedAt(r.getShippedAt())
                .build();
    }

    private static Item item(RmaItem i) {
        return Item.builder()
                .id(i.getId())
                .sku(i.getSku())
                .name(i.getName())
                .quantity(i.getQuantity())
                .unitPrice(i.getUnitPrice())
                .condition(i.getCondition())
                .reason(i.getReason())
                .build();
    }
}
