package com.asm.assistant.tools;

import com.asm.assistant.answer.IntentRouter.Decision;
import com.asm.assistant.answer.IntentRouter.Intent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Maps a routed (domain + entity) intent to the right read-only live call, and packages the result
 * for the answer. For SLA questions it uses the delivery's sla-timeline — the SLA engine's own
 * deterministic verdict — rather than letting the LLM compute compliance.
 */
@Service
@RequiredArgsConstructor
public class LiveDataService {

    private final LiveApiClient api;

    /** available=false → the live source couldn't be reached or the entity wasn't found. */
    public record LiveResult(boolean available, String sourceLabel, Map<String, Object> data) {
        static LiveResult unavailable(String label) { return new LiveResult(false, label, Map.of()); }
        static LiveResult of(String label, Map<String, Object> d) { return new LiveResult(true, label, d); }
    }

    public LiveResult fetch(Decision d) {
        String id = d.entityId();
        if (id == null) return LiveResult.unavailable("aucune référence d'entité");

        if (d.intent() == Intent.DETERMINISTIC) {
            return wrap(api.getSlaTimeline(id), "SLA (moteur SLA — sla-timeline) de " + id);
        }
        return switch (d.domain()) {
            case RMA      -> wrap(api.getReturn(id), "Retour (RMA) " + id);
            case ROUTE    -> wrap(api.getRoute(id), "Tournée " + id);
            case DRIVER   -> wrap(api.getRouteDriverLocation(id), "Position livreur (tournée " + id + ")");
            case DELIVERY, UNKNOWN -> wrap(api.getDelivery(id), "Livraison " + id);
        };
    }

    private LiveResult wrap(Optional<Map<String, Object>> data, String label) {
        return data.map(m -> LiveResult.of(label, m)).orElse(LiveResult.unavailable(label));
    }
}
