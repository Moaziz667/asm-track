package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Handoff;
import com.asm.delivery.entity.HandoffState;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.HandoffRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.route.RouteExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the full driver-to-driver handoff lifecycle (the {@link Handoff} aggregate).
 * Every state transition lives here: request → generate code → confirm, plus
 * cancel and SLA expiry. Real-time notification is delegated to {@link EventPublisher};
 * the legacy {@code route_stops.handoff_*} columns are kept in sync so existing
 * read paths keep working during the transition.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandoffService {

    private final HandoffRepository handoffRepo;
    private final DeliveryRepository deliveryRepo;
    private final RouteStopRepository routeStopRepository;
    private final RouteExecutionService routeExecutionService;
    private final EventPublisher eventPublisher;
    private final AuditLogService auditLogService;
    private final com.asm.delivery.transport.TransportPort transportPort;

    /** Self-reference so REQUIRES_NEW helpers run in their own committed transaction. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private HandoffService self;

    @Value("${handoff.token.ttl-minutes:5}")
    private long tokenTtlMinutes;

    @Value("${handoff.token.max-attempts:5}")
    private int maxAttempts;

    // Crockford base32 minus ambiguous chars (no I, L, O, U) — readable + QR-safe.
    private static final char[] TOKEN_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TOKEN_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<HandoffState> OPEN_STATES =
            List.of(HandoffState.REQUESTED, HandoffState.IN_PROGRESS);

    // ── Initiation ────────────────────────────────────────────────────────────

    /**
     * Open a handoff for an in-field parcel being moved from one driver to another.
     * Idempotent: if an open handoff already exists for the delivery it is reused.
     */
    @Transactional
    public Handoff request(UUID deliveryId, UUID fromDriverId, UUID toDriverId, UserPrincipal actor, String reason) {
        if (fromDriverId == null || toDriverId == null) {
            throw AppException.badRequest("Both drivers are required for a handoff");
        }
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        Handoff existing = handoffRepo.findActiveByDeliveryId(deliveryId).orElse(null);
        if (existing != null) {
            existing.setFromDriverId(fromDriverId);
            existing.setToDriverId(toDriverId);
            existing.setReason(reason);
            handoffRepo.save(existing);
            return existing;
        }

        RouteStop stop = routeStopRepository.findActiveByDeliveryId(deliveryId).orElse(null);

        Handoff handoff = Handoff.builder()
                .deliveryId(deliveryId)
                .routeId(stop != null && stop.getRoute() != null ? stop.getRoute().getId() : null)
                .routeStopId(stop != null ? stop.getId() : null)
                .fromDriverId(fromDriverId)
                .toDriverId(toDriverId)
                .state(HandoffState.REQUESTED)
                .requestedBy(actorName(actor))
                .reason(reason)
                .build();
        handoff = handoffRepo.save(handoff);

        syncStopLegacyFields(stop, handoff);

        auditLogService.logAction(actor, "HANDOFF_REQUESTED", "DELIVERY", deliveryId.toString(),
                Map.of("handoffId", handoff.getId().toString(),
                        "fromDriver", fromDriverId.toString(),
                        "toDriver", toDriverId.toString(),
                        "reason", reason != null ? reason : ""));

        eventPublisher.publishHandoffRequested(handoff, delivery.getOrder());
        return handoff;
    }

    // ── Code generation (sender) ────────────────────────────────────────────────

    @Transactional
    public Handoff generateToken(UUID handoffId, UUID requestingDriverId) {
        Handoff h = load(handoffId);
        if (h.getState().isTerminal()) {
            throw AppException.badRequest("This handoff is already " + h.getState().name().toLowerCase());
        }
        if (!requestingDriverId.equals(h.getFromDriverId())) {
            throw AppException.forbidden("Only the sending driver can generate the handoff code");
        }
        h.setToken(generateCode());
        h.setTokenExpiresAt(LocalDateTime.now().plusMinutes(tokenTtlMinutes));
        h.setTokenAttempts(0);
        h.setState(HandoffState.IN_PROGRESS);
        if (h.getInProgressAt() == null) h.setInProgressAt(LocalDateTime.now());
        handoffRepo.save(h);

        routeStopRepository.findById(orElseStopId(h)).ifPresent(stop -> {
            stop.setHandoffToken(h.getToken());
            stop.setHandoffTokenExpiresAt(h.getTokenExpiresAt());
            routeStopRepository.save(stop);
        });

        log.info("HANDOFF_CODE_GENERATED handoffId={} driverId={}", handoffId, requestingDriverId);
        eventPublisher.publishHandoffCodeReady(h, loadOrder(h));
        return h;
    }

    // ── Confirmation (receiver) ──────────────────────────────────────────────────

    @Transactional
    public Delivery confirm(UUID handoffId, UUID toDriverId, String token,
                            BigDecimal lat, BigDecimal lng, String evidenceUrl, String notes) {
        Handoff h = load(handoffId);
        Delivery delivery = deliveryRepo.findByIdWithOrder(h.getDeliveryId())
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        // Idempotent — already done.
        if (h.getState() == HandoffState.CONFIRMED) {
            return delivery;
        }
        if (h.getState().isTerminal()) {
            throw AppException.badRequest("This handoff is " + h.getState().name().toLowerCase() + " and can no longer be confirmed");
        }
        if (!toDriverId.equals(h.getToDriverId())) {
            throw AppException.forbidden("Only the receiving driver can confirm this handoff");
        }
        if (h.getToken() == null) {
            throw AppException.badRequest("No code has been generated yet — ask the sending driver to show the code");
        }
        if (h.getTokenExpiresAt() != null && h.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            // Persist the expiry in its own committed transaction — the throw below
            // rolls back the caller's transaction, which must NOT undo this.
            self.markExpired(h.getId(), "Code expired");
            throw AppException.badRequest("The handoff code has expired. Ask the sender to generate a new one.");
        }
        if (!constantTimeEquals(h.getToken(), token)) {
            // Record the failed attempt in its own committed transaction so the
            // brute-force lockout survives this method's rollback-on-throw.
            boolean locked = self.registerFailedAttempt(h.getId(), maxAttempts);
            throw AppException.badRequest(locked
                    ? "Too many invalid attempts. Ask the sender to generate a new code."
                    : "Invalid handoff code");
        }

        LocalDateTime now = LocalDateTime.now();
        h.setState(HandoffState.CONFIRMED);
        h.setConfirmedAt(now);
        h.setConfirmLat(lat);
        h.setConfirmLng(lng);
        h.setEvidenceUrl(evidenceUrl);
        h.setNotes(notes);
        h.setToken(null); // single-use: invalidate immediately
        handoffRepo.save(h);

        // Auto-advance: the receiver now physically holds the parcel.
        if (delivery.getStatus() == DeliveryStatus.SCHEDULED || delivery.getStatus() == DeliveryStatus.UNSCHEDULED) {
            delivery.setStatus(DeliveryStatus.PICKED_UP);
            delivery.setPickedUpAt(now);
            deliveryRepo.save(delivery);
            routeExecutionService.syncStopFromDelivery(delivery.getId(), DeliveryStatus.PICKED_UP, now,
                    "Handoff confirmed — package received");
        }

        // Clear legacy + active pointer on the stop.
        routeStopRepository.findById(orElseStopId(h)).ifPresent(stop -> {
            stop.setRequiresHandoff(false);
            stop.setHandoffConfirmedAt(now);
            stop.setHandoffToken(null);
            stop.setHandoffTokenExpiresAt(null);
            stop.setActiveHandoffId(null);
            routeStopRepository.save(stop);
        });

        Order order = delivery.getOrder();
        auditLogService.logAction(null, "HANDOFF_CONFIRMED", "DELIVERY", h.getDeliveryId().toString(),
                Map.of("handoffId", h.getId().toString(),
                        "fromDriver", h.getFromDriverId() != null ? h.getFromDriverId().toString() : "",
                        "toDriver", toDriverId.toString(),
                        "client", order != null && order.getClientName() != null ? order.getClientName() : "N/A"));

        log.info("HANDOFF_CONFIRMED handoffId={} from={} to={}", h.getId(), h.getFromDriverId(), toDriverId);
        eventPublisher.publishHandoffConfirmed(h, order);
        return delivery;
    }

    // ── Cancel / expire ──────────────────────────────────────────────────────────

    @Transactional
    public Handoff cancel(UUID handoffId, UserPrincipal actor, String reason) {
        Handoff h = load(handoffId);
        if (h.getState().isTerminal()) return h;
        h.setState(HandoffState.CANCELLED);
        h.setCancelledAt(LocalDateTime.now());
        h.setCancelledBy(actorName(actor));
        h.setReason(reason);
        h.setToken(null);
        handoffRepo.save(h);
        revertCustodyToSender(h);
        clearStopPointer(h);
        auditLogService.logAction(actor, "HANDOFF_CANCELLED", "DELIVERY", h.getDeliveryId().toString(),
                Map.of("handoffId", h.getId().toString(), "reason", reason != null ? reason : ""));
        log.info("HANDOFF_CANCELLED handoffId={} reason={}", h.getId(), reason);
        eventPublisher.publishHandoffCancelled(h, loadOrder(h));
        return h;
    }

    /** Called by the SLA sweeper when an open handoff times out. */
    @Transactional
    public void expire(UUID handoffId, String reason) {
        Handoff h = handoffRepo.findById(handoffId).orElse(null);
        if (h == null || h.getState().isTerminal()) return;
        expireInternal(h, reason);
        handoffRepo.save(h);
        revertCustodyToSender(h);
        clearStopPointer(h);
        log.warn("HANDOFF_EXPIRED handoffId={} deliveryId={} reason={} — custody reverted to sender {}",
                h.getId(), h.getDeliveryId(), reason, h.getFromDriverId());
        eventPublisher.publishHandoffCancelled(h, loadOrder(h));
    }

    private void expireInternal(Handoff h, String reason) {
        h.setState(HandoffState.EXPIRED);
        h.setExpiredAt(LocalDateTime.now());
        h.setReason(reason);
        h.setToken(null);
    }

    /**
     * Increments the failed-code counter and expires the handoff once the cap is
     * hit. Runs in its own transaction so it commits even though the caller throws
     * (which rolls the caller's transaction back). Returns true when now locked.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean registerFailedAttempt(UUID handoffId, int maxAttempts) {
        Handoff h = handoffRepo.findById(handoffId).orElse(null);
        if (h == null || h.getState().isTerminal()) return true;
        int attempts = (h.getTokenAttempts() == null ? 0 : h.getTokenAttempts()) + 1;
        h.setTokenAttempts(attempts);
        boolean locked = attempts >= maxAttempts;
        if (locked) {
            expireInternal(h, "Too many invalid code attempts");
        }
        handoffRepo.save(h);
        return locked;
    }

    /** Expires a handoff in its own committed transaction (survives caller rollback). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markExpired(UUID handoffId, String reason) {
        handoffRepo.findById(handoffId).ifPresent(h -> {
            if (h.getState().isTerminal()) return;
            expireInternal(h, reason);
            handoffRepo.save(h);
        });
    }

    // ── Queries (initial load; live updates arrive via WebSocket) ───────────────

    @Transactional(readOnly = true)
    public List<com.asm.delivery.dto.response.HandoffResponse> listForDriver(UUID driverId) {
        java.util.LinkedHashMap<UUID, Handoff> merged = new java.util.LinkedHashMap<>();
        handoffRepo.findByToDriverIdAndStateIn(driverId, OPEN_STATES).forEach(h -> merged.put(h.getId(), h));
        handoffRepo.findByFromDriverIdAndStateIn(driverId, OPEN_STATES).forEach(h -> merged.put(h.getId(), h));
        return merged.values().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<com.asm.delivery.dto.response.HandoffResponse> listForAdmin(HandoffState state) {
        List<Handoff> all = (state != null)
                ? handoffRepo.findAll().stream().filter(h -> h.getState() == state).toList()
                : handoffRepo.findAll();
        return all.stream().map(this::toResponse).toList();
    }

    private com.asm.delivery.dto.response.HandoffResponse toResponse(Handoff h) {
        Order order = loadOrder(h);
        return com.asm.delivery.dto.response.HandoffResponse.builder()
                .id(h.getId().toString())
                .state(h.getState() != null ? h.getState().name() : null)
                .deliveryId(h.getDeliveryId() != null ? h.getDeliveryId().toString() : null)
                .routeId(h.getRouteId() != null ? h.getRouteId().toString() : null)
                .erpOrderId(order != null ? order.resolveRef() : null)
                .clientName(order != null ? order.getClientName() : null)
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .fromDriverId(h.getFromDriverId() != null ? h.getFromDriverId().toString() : null)
                .fromDriverName(driverName(h.getFromDriverId()))
                .toDriverId(h.getToDriverId() != null ? h.getToDriverId().toString() : null)
                .toDriverName(driverName(h.getToDriverId()))
                .requestedAt(h.getRequestedAt())
                .tokenExpiresAt(h.getTokenExpiresAt())
                .confirmedAt(h.getConfirmedAt())
                .reason(h.getReason())
                .build();
    }

    private String driverName(UUID driverId) {
        if (driverId == null) return null;
        try {
            var d = transportPort.getDriver(driverId.toString());
            return d != null ? d.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Handoff load(UUID handoffId) {
        return handoffRepo.findById(handoffId)
                .orElseThrow(() -> AppException.notFound("Handoff not found"));
    }

    private Order loadOrder(Handoff h) {
        return deliveryRepo.findByIdWithOrder(h.getDeliveryId()).map(Delivery::getOrder).orElse(null);
    }

    private UUID orElseStopId(Handoff h) {
        return h.getRouteStopId() != null ? h.getRouteStopId() : new UUID(0L, 0L);
    }

    private void syncStopLegacyFields(RouteStop stop, Handoff handoff) {
        if (stop == null) return;
        stop.setRequiresHandoff(true);
        stop.setHandoffFromDriverId(handoff.getFromDriverId());
        stop.setHandoffToDriverId(handoff.getToDriverId());
        stop.setHandoffConfirmedAt(null);
        stop.setActiveHandoffId(handoff.getId());
        routeStopRepository.save(stop);
    }

    /**
     * When a handoff is cancelled or expires <b>without</b> being confirmed, the transfer
     * never physically happened — the sending driver still holds the parcel. Return
     * responsibility to them so the unconfirmed receiver can never deliver a parcel they
     * never received (the stop's {@code requiresHandoff} flag is about to be cleared).
     * Dispatch is alerted via the cancelled event and re-plans the stop as needed.
     */
    private void revertCustodyToSender(Handoff h) {
        if (h.getFromDriverId() == null) return;
        deliveryRepo.findByIdWithOrder(h.getDeliveryId()).ifPresent(delivery -> {
            // Only revert if it didn't already reach a physical/terminal state under the receiver.
            if (delivery.getStatus() == DeliveryStatus.SCHEDULED || delivery.getStatus() == DeliveryStatus.UNSCHEDULED) {
                delivery.setDriverId(h.getFromDriverId());
                delivery.setStatus(DeliveryStatus.PICKED_UP);
                if (delivery.getPickedUpAt() == null) delivery.setPickedUpAt(LocalDateTime.now());
                deliveryRepo.save(delivery);
            }
        });
    }

    private void clearStopPointer(Handoff h) {
        if (h.getRouteStopId() == null) return;
        routeStopRepository.findById(h.getRouteStopId()).ifPresent(stop -> {
            stop.setActiveHandoffId(null);
            stop.setRequiresHandoff(false);
            stop.setHandoffToken(null);
            stop.setHandoffTokenExpiresAt(null);
            routeStopRepository.save(stop);
        });
    }

    private String actorName(UserPrincipal p) {
        if (p == null) return "SYSTEM";
        if (p.getName() != null) return p.getName();
        return p.getUserId() != null ? p.getUserId() : "SYSTEM";
    }

    private static String generateCode() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET[RANDOM.nextInt(TOKEN_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Constant-time, case-insensitive comparison to avoid timing oracles. */
    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) return false;
        byte[] a = expected.toUpperCase().getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.trim().toUpperCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
