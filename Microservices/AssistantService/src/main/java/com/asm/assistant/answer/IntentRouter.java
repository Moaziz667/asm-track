package com.asm.assistant.answer;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides where an answer must come from — the boundary that stops the assistant becoming "a chatbot
 * over the database". Practical and rule-based (no agentic planning):
 *
 * <ul>
 *   <li><b>DETERMINISTIC</b> — a question about SLA compliance/lateness of a specific entity goes to
 *       the SLA engine's own output (sla-timeline), never the LLM's guesswork.</li>
 *   <li><b>LIVE_API</b> — "current status / where is …" about a specific delivery, return (RMA), route
 *       or driver goes to the live read APIs, never vector search. Also covers the enumeration of a
 *       <em>bounded</em> reference collection ("les dépôts ?"): asking which depots exist is a question
 *       about this tenant's data, not about the documentation. Only small, stable collections qualify —
 *       enumerating deliveries would pour thousands of rows into the context window.</li>
 *   <li><b>RAG</b> — how/why/what knowledge questions (SLA rules, ERP sync, RMA process, tournée
 *       lifecycle, COD, RBAC, multi-tenant…) go to the grounded corpus.</li>
 * </ul>
 *
 * REFUSAL is not decided here: it emerges downstream when retrieval finds no adequate evidence, so we
 * don't maintain a brittle out-of-scope keyword list.
 */
@Component
public class IntentRouter {

    public enum Intent { RAG, LIVE_API, DETERMINISTIC }

    public enum Domain { DELIVERY, RMA, ROUTE, DRIVER, UNKNOWN }

    public record Decision(Intent intent, Domain domain, String entityId) {}

    // A UUID, or a reference code like "D-1234" / "RMA1234" / "T4-… " — a concrete entity reference.
    private static final Pattern ENTITY_REF = Pattern.compile(
            "\\b([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            + "|[A-Za-z]{1,5}-?\\d{3,})\\b");

    private static final Pattern LIVE_HINT = Pattern.compile(
            "(?i)\\b(statut|status|où|ou est|position|localis|en cours|actuel|actuelle|maintenant|"
            + "en ce moment|aujourd'hui|current|now|live|en retard|retard)\\b");

    private static final Pattern SLA_HINT = Pattern.compile(
            "(?i)\\b(sla|respect|respecté|viol|violation|en retard|dépass|deadline|échéance|à temps|a temps)\\b");

    public Decision route(String query) {
        String q = query == null ? "" : query;
        Domain domain = detectDomain(q);
        Matcher ref = ENTITY_REF.matcher(q);
        String entityId = ref.find() ? ref.group(1) : null;

        // A concrete entity + a "state" question → live/deterministic, not RAG.
        if (entityId != null && (LIVE_HINT.matcher(q).find() || SLA_HINT.matcher(q).find())) {
            if (SLA_HINT.matcher(q).find()) {
                return new Decision(Intent.DETERMINISTIC, domain == Domain.UNKNOWN ? Domain.DELIVERY : domain, entityId);
            }
            return new Decision(Intent.LIVE_API, domain == Domain.UNKNOWN ? Domain.DELIVERY : domain, entityId);
        }
        return new Decision(Intent.RAG, domain, entityId);
    }

    private Domain detectDomain(String q) {
        String low = q.toLowerCase();
        if (low.matches(".*\\b(retour|retours|rma|return)\\b.*")) return Domain.RMA;
        if (low.matches(".*\\b(tourn[ée]e|tournee|itin[ée]raire|route)\\b.*")) return Domain.ROUTE;
        if (low.matches(".*\\b(livreur|chauffeur|driver|conducteur)\\b.*")) return Domain.DRIVER;
        if (low.matches(".*\\b(livraison|colis|commande|delivery|expédition)\\b.*")) return Domain.DELIVERY;
        return Domain.UNKNOWN;
    }
}
