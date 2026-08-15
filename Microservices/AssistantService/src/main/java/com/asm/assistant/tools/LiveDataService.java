package com.asm.assistant.tools;

import com.asm.assistant.answer.IntentRouter.Decision;
import com.asm.assistant.answer.IntentRouter.Intent;
import com.asm.assistant.observability.RagMetrics;
import com.asm.assistant.tools.LiveApiClient.LiveLookup;
import com.asm.assistant.tools.LiveApiClient.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Maps a routed (domain + entity) intent to the right read-only live call, and packages the result
 * for the answer. For SLA questions it uses the delivery's sla-timeline — the SLA engine's own
 * deterministic verdict — rather than letting the LLM compute compliance.
 */
@Service
@RequiredArgsConstructor
public class LiveDataService {

    private final LiveApiClient api;
    private final RagMetrics metrics;

    /**
     * The live read as the answer layer sees it. {@code NOT_FOUND} and {@code UNAVAILABLE} are kept
     * apart on purpose: the first is a factual answer ("no such delivery"), the second an outage.
     */
    public record LiveResult(Status status, String sourceLabel, Map<String, Object> data) {
        static LiveResult unavailable(String label) { return new LiveResult(Status.UNAVAILABLE, label, Map.of()); }
        public boolean available() { return status == Status.FOUND; }
        public boolean notFound()  { return status == Status.NOT_FOUND; }
    }

    public LiveResult fetch(Decision d) {
        String id = d.entityId();
        if (id == null) return LiveResult.unavailable("aucune référence d'entité");

        LiveResult r = metrics.record(metrics.toolTimer, () -> {
            if (d.intent() == Intent.DETERMINISTIC) {
                return wrap(api.getSlaTimeline(id), "SLA (moteur SLA — sla-timeline) de " + id);
            }
            return switch (d.domain()) {
                case RMA      -> wrap(api.getReturn(id), "Retour (RMA) " + id);
                case ROUTE    -> wrap(api.getRoute(id), "Tournée " + id);
                case DRIVER   -> wrap(api.getRouteDriverLocation(id), "Position livreur (tournée " + id + ")");
                case DELIVERY, UNKNOWN -> wrap(api.getDelivery(id), "Livraison " + id);
            };
        });
        metrics.toolCall(d.domain().name(), r.available());
        return r;
    }

    /** Runs a tool chosen from the catalogue (the model-driven path). */
    public LiveResult run(LiveToolCatalog.Tool tool, String entityId) {
        String label = tool.needsId() ? tool.label() + " " + entityId : tool.label();
        LiveResult r = metrics.record(metrics.toolTimer, () -> wrap(api.call(tool, entityId), label));
        metrics.toolCall(tool.name(), r.available());
        return r;
    }

    private LiveResult wrap(LiveLookup lookup, String label) {
        return new LiveResult(lookup.status(), label, lookup.body());
    }
}
