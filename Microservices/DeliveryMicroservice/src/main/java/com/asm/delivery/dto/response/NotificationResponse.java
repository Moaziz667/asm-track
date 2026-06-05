package com.asm.delivery.dto.response;

import com.asm.delivery.entity.Notification;
import lombok.Builder;
import lombok.Data;

import java.time.ZoneOffset;
import java.util.Map;

/** Admin-facing notification shape (mirrors the live STOMP event fields the UI already consumes). */
@Data
@Builder
public class NotificationResponse {
    private String id;
    private String event;        // eventType, e.g. "erp.sync_failed"
    private String severity;     // critical | warning | info
    private String title;
    private String message;
    private String orderId;      // orderRef
    private String deliveryId;
    private String routeId;
    private String driverId;
    private String driverName;
    private String clientName;
    private Map<String, Object> eventParams;
    private boolean read;
    private boolean acknowledged;
    private String acknowledgedBy;
    private long timestamp;      // createdAt epoch millis

    public static NotificationResponse from(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId() != null ? n.getId().toString() : null)
                .event(n.getEventType())
                .severity(n.getSeverity())
                .title(n.getTitle())
                .message(n.getMessage())
                .orderId(n.getOrderRef())
                .deliveryId(n.getDeliveryId())
                .routeId(n.getRouteId())
                .driverId(n.getDriverId())
                .driverName(n.getDriverName())
                .clientName(n.getClientName())
                .eventParams(n.getPayload())
                .read(n.isRead())
                .acknowledged(n.isAcknowledged())
                .acknowledgedBy(n.getAcknowledgedBy())
                .timestamp(n.getCreatedAt() != null
                        ? n.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli() : 0L)
                .build();
    }
}
