package com.asm.delivery.sla;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the {@link SlaState} table — the single source of truth. Updated event-driven from every
 * lifecycle transition hook and by one reconciliation tick (replacing the old 20s SlaMonitoringService
 * loop). Alerts fire only on health <em>transitions</em> with persisted dedup, so a restart never
 * re-floods open breaches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaStateService {

    private final SlaStateRepository repo;
    private final SlaEvaluator evaluator;
    private final DeliveryRepository deliveryRepo;
    private final EventPublisher eventPublisher;
    private final SlaPolicy policy;

    private static final List<DeliveryStatus> LIVE = List.of(
            DeliveryStatus.UNSCHEDULED, DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT);

    /** Recompute and persist a delivery's SLA; emit one {@code sla.alert} on a new alertable health. */
    @Transactional
    public void refresh(Delivery d) {
        if (d == null || d.getId() == null) return;
        LocalDateTime now = LocalDateTime.now();
        SlaEvaluator.Result r = evaluator.evaluate(d, now);

        SlaState state = repo.findByDeliveryId(d.getId()).orElseGet(() ->
                SlaState.builder().deliveryId(d.getId()).phase(r.phase()).health(SlaHealth.ON_TRACK).build());

        boolean transition = state.getPhase() != r.phase() || state.getHealth() != r.health();

        state.setPhase(r.phase());
        state.setHealth(r.health());
        state.setDueAt(r.dueAt());
        state.setLateMinutes(r.lateMinutes());
        state.setAttributableToDriver(r.attributableToDriver());
        state.setReasonKey(r.reasonKey());
        state.setReasonParams(r.reasonParams());
        if (r.health() == SlaHealth.AT_RISK && state.getAtRiskAt() == null)  state.setAtRiskAt(now);
        if (r.health() == SlaHealth.BREACHED && state.getBreachedAt() == null) state.setBreachedAt(now);
        if (transition) state.setLastTransitionAt(now);

        recordPhaseHealth(state, r.phase(), r.health());

        boolean inGrace = state.getSuppressAlertsUntil() != null && now.isBefore(state.getSuppressAlertsUntil());
        if (!inGrace && r.health().isAlertable() && r.health() != state.getLastAlertedHealth()) {
            eventPublisher.publishSlaAlert(d, r.phase(), r.health(), r.reasonKey(), r.reasonParams(), r.dueAt());
            state.setLastAlertedHealth(r.health());
        }
        // Back to healthy/resolved → forget the alert so a future re-breach alerts again.
        if (!r.health().isAlertable()) state.setLastAlertedHealth(null);

        repo.save(state);
    }

    /**
     * Remember the worst health a phase ever reached, keyed by phase name. For a terminal phase
     * (DELIVERED/PARTIAL) we attribute the outcome to the DELIVERY phase so the stepper's delivery
     * node reflects a late arrival. Severity rank: BREACHED/LATE &gt; AT_RISK &gt; everything else.
     */
    private void recordPhaseHealth(SlaState state, SlaPhase phase, SlaHealth health) {
        String key = switch (phase) {
            case DELIVERED, PARTIAL, FAILED, CANCELLED -> SlaPhase.DELIVERY.name();
            default -> phase.name();
        };
        Map<String, String> map = state.getPhaseHealth() != null
                ? new java.util.HashMap<>(state.getPhaseHealth()) : new java.util.HashMap<>();
        SlaHealth existing = map.containsKey(key) ? safeHealth(map.get(key)) : null;
        if (existing == null || rank(health) > rank(existing)) {
            map.put(key, health.name());
            state.setPhaseHealth(map);
        }
    }

    private SlaHealth safeHealth(String s) {
        try { return SlaHealth.valueOf(s); } catch (Exception e) { return null; }
    }

    /** Higher = worse, so the stepper colours a passed phase by its worst moment. */
    private int rank(SlaHealth h) {
        return switch (h) {
            case BREACHED, LATE -> 3;
            case AT_RISK -> 2;
            default -> 1; // ON_TRACK / MET / NONE
        };
    }

    @Transactional
    public void refreshById(UUID deliveryId) {
        if (deliveryId != null) deliveryRepo.findByIdWithOrder(deliveryId).ifPresent(this::refresh);
    }

    /**
     * After a re-plan or stop removal that re-pools a delivery, briefly suppress its planning alarm so
     * it is not re-flagged "as if newly imported". Health stays truthful; only the alert is held.
     */
    @Transactional
    public void applyReplanGrace(UUID deliveryId) {
        if (deliveryId == null) return;
        repo.findByDeliveryId(deliveryId).ifPresent(s -> {
            s.setSuppressAlertsUntil(LocalDateTime.now().plusMinutes(policy.replanGraceMinutes()));
            s.setLastAlertedHealth(null);
            repo.save(s);
        });
    }

    /** Single periodic tick that advances live deliveries toward AT_RISK/BREACHED as time passes. */
    @Scheduled(fixedDelayString = "${app.sla.check-interval-ms:20000}")
    @Transactional
    public void reconcile() {
        for (DeliveryStatus st : LIVE) {
            deliveryRepo.findByStatus(st).forEach(this::refresh);
        }
    }
}
