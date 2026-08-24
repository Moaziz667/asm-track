package com.asm.assistant.tools;

import com.asm.assistant.answer.IntentRouter.Decision;
import com.asm.assistant.answer.IntentRouter.Domain;
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
    private final ReferenceResolver resolver;

    /**
     * The live read as the answer layer sees it. {@code NOT_FOUND} and {@code UNAVAILABLE} are kept
     * apart on purpose: the first is a factual answer ("no such delivery"), the second an outage.
     */
    public record LiveResult(Status status, String sourceLabel, Map<String, Object> data, String message) {
        /** The common case: the generic wording derived from {@code sourceLabel} is good enough. */
        public LiveResult(Status status, String sourceLabel, Map<String, Object> data) {
            this(status, sourceLabel, data, null);
        }

        static LiveResult unavailable(String label) {
            return new LiveResult(Status.UNAVAILABLE, label, Map.of(), null);
        }
        public boolean available() { return status == Status.FOUND; }
        public boolean notFound()  { return status == Status.NOT_FOUND; }
        /** Set when the generic wording would be wrong — e.g. a reference matching several deliveries. */
        public boolean hasMessage() { return message != null && !message.isBlank(); }
    }

    public LiveResult fetch(Decision d) {
        String raw = d.entityId();
        if (raw == null) return LiveResult.unavailable("aucune référence d'entité");

        // Only the delivery endpoints are reference-searchable; routes and RMAs keep the raw value.
        boolean deliveryLookup = d.intent() == Intent.DETERMINISTIC
                || d.domain() == Domain.DELIVERY
                || d.domain() == Domain.UNKNOWN;

        String id = raw;
        if (deliveryLookup) {
            ReferenceResolver.Resolution res = resolver.resolveDelivery(raw);
            if (!res.usable()) {
                return resolutionFailure(res, raw);
            }
            id = res.id();
        }

        String resolved = id;
        LiveResult r = metrics.record(metrics.toolTimer, () -> {
            if (d.intent() == Intent.DETERMINISTIC) {
                return wrap(api.getSlaTimeline(resolved), "SLA (moteur SLA — sla-timeline) de " + raw);
            }
            return switch (d.domain()) {
                case RMA      -> wrap(api.getReturn(resolved), "Retour (RMA) " + raw);
                case ROUTE    -> wrap(api.getRoute(resolved), "Tournée " + raw);
                case DRIVER   -> wrap(api.getRouteDriverLocation(resolved), "Position livreur (tournée " + raw + ")");
                case DELIVERY, UNKNOWN -> wrap(api.getDelivery(resolved), "Livraison " + raw);
            };
        });
        metrics.toolCall(d.domain().name(), r.available());
        return r;
    }

    /**
     * A reference that resolved to nothing is a factual answer, not an outage — except when the search
     * itself was unreachable, which must stay distinguishable so the answer does not blame the user for
     * a platform problem.
     */
    private LiveResult resolutionFailure(ReferenceResolver.Resolution res, String raw) {
        String label = "Livraison « " + raw + " »";
        return switch (res.outcome()) {
            case UNAVAILABLE -> LiveResult.unavailable("recherche de la référence " + raw);
            case AMBIGUOUS -> new LiveResult(Status.NOT_FOUND, label, Map.of(),
                    "La référence « " + raw + " » correspond à " + res.matches()
                    + " livraisons (reprises ou reliquats). Précisez laquelle vous voulez.");
            default -> new LiveResult(Status.NOT_FOUND, label, Map.of(),
                    "Aucune livraison ne porte la référence « " + raw + " » chez vous.");
        };
    }

    /** Runs a tool chosen from the catalogue (the model-driven path). */
    public LiveResult run(LiveToolCatalog.Tool tool, String entityId) {
        String label = tool.needsId() ? tool.label() + " " + entityId : tool.label();
        String id = entityId;
        // The model extracts whatever the question named — an ERP reference or a customer name just as
        // often as a UUID — so the delivery tools get the same resolution step as the routed path.
        if (tool.needsId() && tool.uri().startsWith("/api/v1/admin/deliveries/")) {
            ReferenceResolver.Resolution res = resolver.resolveDelivery(entityId);
            if (!res.usable()) {
                return resolutionFailure(res, entityId);
            }
            id = res.id();
        }
        String resolved = id;
        LiveResult r = metrics.record(metrics.toolTimer, () -> wrap(api.call(tool, resolved), label));
        metrics.toolCall(tool.name(), r.available());
        return r;
    }

    private LiveResult wrap(LiveLookup lookup, String label) {
        return new LiveResult(lookup.status(), label, lookup.body(), null);
    }
}
