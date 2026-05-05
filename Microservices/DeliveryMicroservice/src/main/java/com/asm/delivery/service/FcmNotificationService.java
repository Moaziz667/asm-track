package com.asm.delivery.service;

import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "fcm.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class FcmNotificationService {

    private final TransportPort transportPort;

    public void sendToDriver(String driverId, String title, String body) {
        try {
            DriverDTO driver = transportPort.getDriver(driverId);
            if (driver == null || driver.getFcmToken() == null || driver.getFcmToken().isBlank()) {
                log.debug("FCM: driver {} has no token, skipping", driverId);
                return;
            }
            Message msg = Message.builder()
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .setToken(driver.getFcmToken())
                    .build();
            String messageId = FirebaseMessaging.getInstance().send(msg);
            log.info("FCM sent to driver {} msgId={}", driverId, messageId);
        } catch (Exception e) {
            log.warn("FCM send to driver {} failed: {}", driverId, e.getMessage());
        }
    }
}
