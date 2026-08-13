package com.asm.delivery.messaging;

import com.asm.delivery.security.UserPrincipal;
import com.asm.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A driver is online when his app is holding its realtime connection — not when he last moved.
 *
 * <p>Availability used to be inferred from the age of the last GPS fix, which answered a different
 * question and answered it badly in both directions. A driver stopped at a customer for a quarter of
 * an hour was declared unreachable while looking at his screen; a driver who had never sent a fix at
 * all escaped the rule entirely, because a null timestamp is never "older than" anything. This
 * observes the connection instead of guessing from its side effects.
 *
 * <p>Sessions are counted rather than flagged: the app reconnects on its own — a tunnel, a screen
 * lock, a token refresh — and the new session can be established before the old one is reported
 * closed. Publishing OFFLINE on the first disconnect would then blink the driver out while he is
 * plainly connected.
 *
 * <p>A dead connection still reports itself: the endpoint runs over SockJS, whose server heartbeat
 * fails to write to a phone that has lost the network, closing the session and firing the disconnect
 * within about a minute. Nothing here has to time anything out.
 *
 * <p>The roster below is the reconciler. This map lives in memory, so a restart of this service
 * would otherwise leave DriverService believing in sessions that no longer exist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DriverPresenceTracker {

    private final DriverCommandPublisher publisher;

    /** How long a driver may be gone before it counts as an absence. */
    private static final long GRACE_SECONDS = 45;

    /** sessionId → who it belongs to. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** One thread: these tasks are a map lookup and, rarely, a publish. */
    private final ScheduledExecutorService grace = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "driver-presence-grace");
        t.setDaemon(true);          // must never hold the JVM open on shutdown
        return t;
    });

    private record Session(UUID driverId, UUID companyId) {
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        grace.shutdownNow();
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        UserPrincipal driver = driverOf(accessor.getUser());
        if (sessionId == null || driver == null) return;

        UUID driverId = UUID.fromString(driver.getUserId());
        boolean first = sessions.values().stream().noneMatch(s -> s.driverId().equals(driverId));
        sessions.put(sessionId, new Session(driverId, driver.getCompanyId()));
        if (first) {
            publish(driver.getCompanyId(), driverId, true);
            log.info("Driver presence: {} connected", driverId);
        }
    }

    /**
     * A closed session is not an absence yet.
     *
     * <p>Mobile connections drop constantly — a tunnel, a handover between masts, a screen lock —
     * and the app is back within seconds. Announcing OFFLINE at once would blink a working driver
     * out of the dispatcher's screen several times an hour, and this deployment makes it worse: the
     * sockets here are being recycled about every ninety seconds, so an immediate announcement would
     * flap a perfectly healthy driver on and off all day.
     *
     * <p>So the departure is held for a grace period and only confirmed if he has not come back. The
     * check is cheap and the delay is invisible: a driver who really closes his app is offline within
     * the minute, which is still far faster than the sweep it replaces.
     */
    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        Session gone = sessions.remove(event.getSessionId());
        if (gone == null) return;
        if (hasSession(gone.driverId())) return;         // another tab or a reconnect already took over

        grace.schedule(() -> {
            if (hasSession(gone.driverId())) return;     // he came back during the grace period
            publish(gone.companyId(), gone.driverId(), false);
            log.info("Driver presence: {} disconnected", gone.driverId());
        }, GRACE_SECONDS, TimeUnit.SECONDS);
    }

    private boolean hasSession(UUID driverId) {
        return sessions.values().stream().anyMatch(s -> s.driverId().equals(driverId));
    }

    /**
     * Re-states who is connected, every few minutes.
     *
     * <p>Two holes close here. A message lost while DriverService was down would otherwise leave a
     * driver wrong until he reconnects — which, for someone working an eight-hour shift, means all
     * day. And DriverService's own sweep dates a driver from this signal, so a long, quiet, perfectly
     * healthy session has to keep saying so.
     */
    @Scheduled(fixedDelay = 240_000)
    public void republishRoster() {
        sessions.values().stream()
                .collect(java.util.stream.Collectors.toMap(Session::driverId, Session::companyId, (a, b) -> a))
                .forEach((driverId, companyId) -> publish(companyId, driverId, true));
    }

    /**
     * The tenant travels as an AMQP header, stamped from the TenantContext at publish time — and a
     * WebSocket event thread has none. The principal carries it, so it is restored for the call and
     * cleared straight after: this runs on a shared pool, and a leaked tenant would follow the thread
     * onto another company's work.
     */
    private void publish(UUID companyId, UUID driverId, boolean connected) {
        UUID previous = TenantContext.get();
        try {
            if (companyId != null) TenantContext.set(companyId);
            publisher.publishPresence(driverId, connected);
        } finally {
            if (previous != null) TenantContext.set(previous);
            else TenantContext.clear();
        }
    }

    private static UserPrincipal driverOf(Principal principal) {
        if (!(principal instanceof Authentication auth)) return null;
        if (!(auth.getPrincipal() instanceof UserPrincipal user)) return null;
        // Staff sessions outnumber drivers' and say nothing about a driver's availability.
        return "DRIVER".equals(user.getRole()) && user.getUserId() != null ? user : null;
    }
}
