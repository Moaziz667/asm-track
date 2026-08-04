package com.asm.delivery.service;

import com.asm.tenant.TenantContext;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "fcm.enabled", havingValue = "true")
@Slf4j
public class FcmNotificationService {

    private final TransportPort transportPort;
    private final Counter sent;
    private final Counter skippedNoToken;
    private final Counter errors;

    /**
     * Every skip/failure here used to be a {@code log.debug} — invisible in production, which is how
     * "notifications stopped working" went undiagnosed after the multi-tenant migration. Each outcome
     * now increments a counter and logs at WARN with the tenant, so a driver-lookup returning null
     * (e.g. tenant context lost on the send path → lookup hit the wrong schema) is observable.
     */
    public FcmNotificationService(TransportPort transportPort, ObjectProvider<MeterRegistry> meterRegistry) {
        this.transportPort = transportPort;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.sent = counter(registry, "fcm.sent");
        this.skippedNoToken = counter(registry, "fcm.skipped_no_token");
        this.errors = counter(registry, "fcm.error");
    }

    private static Counter counter(MeterRegistry registry, String name) {
        return registry != null ? registry.counter(name) : null;
    }

    private static void increment(Counter c) {
        if (c != null) c.increment();
    }

    public void sendToDriver(String driverId, String title, String body) {
        sendToDriver(driverId, title, body, "GENERAL");
    }

    public void sendToDriver(String driverId, String title, String body, String type) {
        try {
            DriverDTO driver = lookupDriverWithToken(driverId);
            if (driver == null) return;
            Message msg = Message.builder()
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .putData("type", type != null ? type : "GENERAL")
                    .setToken(driver.getFcmToken())
                    .build();
            String messageId = FirebaseMessaging.getInstance().send(msg);
            increment(sent);
            log.info("FCM sent to driver {} type={} msgId={}", driverId, type, messageId);
        } catch (Exception e) {
            increment(errors);
            log.warn("FCM send to driver {} failed (companyId={}): {}", driverId, TenantContext.get(), e.getMessage());
        }
    }

    public void sendDataToDriver(String driverId, java.util.Map<String, String> data) {
        try {
            DriverDTO driver = lookupDriverWithToken(driverId);
            if (driver == null) return;
            Message msg = Message.builder()
                    .putAllData(data)
                    .setToken(driver.getFcmToken())
                    .build();
            String messageId = FirebaseMessaging.getInstance().send(msg);
            increment(sent);
            log.info("FCM data-only sent to driver {} msgId={}", driverId, messageId);
        } catch (Exception e) {
            increment(errors);
            log.warn("FCM data-only send to driver {} failed (companyId={}): {}", driverId, TenantContext.get(), e.getMessage());
        }
    }

    /**
     * Resolves the driver and requires an FCM token. A null here is a LOUD condition: either the
     * driver never registered a token (app not logged in / gateway rejected the registration for a
     * user without an org claim) or the lookup ran without/with the wrong tenant context and hit
     * another schema. Both must be visible in logs and metrics, not debug-level noise.
     */
    private DriverDTO lookupDriverWithToken(String driverId) {
        DriverDTO driver = transportPort.getDriver(driverId);
        if (driver == null || driver.getFcmToken() == null || driver.getFcmToken().isBlank()) {
            increment(skippedNoToken);
            log.warn("FCM skipped — driver {} {} (companyId={})",
                    driverId,
                    driver == null ? "not found (tenant context lost or wrong schema?)" : "has no registered token",
                    TenantContext.get());
            return null;
        }
        return driver;
    }
}
