package com.asm.delivery.notification;

import com.asm.delivery.entity.Notification;
import com.asm.delivery.event.CloudEventWrapper;
import com.asm.delivery.service.FcmNotificationService;
import com.asm.delivery.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The single delivery channel for operational events: STOMP broadcast, FCM push, and durable
 * notification persistence. {@code EventPublisher} builds the domain payloads and hands them here,
 * so the "how it reaches the user" concern lives in one place (it used to be tangled across every
 * publish method in EventPublisher, which is where the silent-FCM bug hid).
 */
@Component
@Slf4j
public class NotificationGateway {

    private final SimpMessagingTemplate ws;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** Optional: absent when {@code fcm.enabled=false}; pushes degrade to a no-op. */
    @Autowired(required = false)
    private FcmNotificationService fcm;

    public NotificationGateway(SimpMessagingTemplate ws, NotificationService notificationService, ObjectMapper objectMapper) {
        this.ws = ws;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /** Broadcast a CloudEvent envelope to a STOMP topic (admin / driver / public channels). */
    public void broadcast(String destination, Object envelope) {
        ws.convertAndSend(destination, envelope);
    }

    /** Persist a durable admin notification. */
    public void record(Notification n) {
        notificationService.record(n);
    }

    /**
     * Data-only FCM push to one driver, wrapping the payload in a CloudEvent the app parses.
     * No-ops when FCM is disabled or the driver id is null; never throws into the caller.
     */
    public void pushToDriver(String driverId, String eventType, Object payload) {
        if (fcm == null || driverId == null) return;
        try {
            CloudEventWrapper<Object> envelope = CloudEventWrapper.builder()
                    .source("/delivery-service").type(eventType).data(payload).build();
            String json = objectMapper.writeValueAsString(envelope);
            fcm.sendDataToDriver(driverId, Map.of("payload", json, "event_type", eventType));
        } catch (Exception e) {
            log.warn("Failed to push FCM payload for driverId={}: {}", driverId, e.getMessage());
        }
    }
}
