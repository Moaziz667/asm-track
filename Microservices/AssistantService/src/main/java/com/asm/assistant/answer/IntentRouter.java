package com.asm.assistant.answer;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
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

    /**
     * A UUID, or a reference code as an ERP prints it. Both supported ERPs must be covered, and they do
     * not agree on shape:
     * <ul>
     *   <li>ERPNext names documents in dashed segments — {@code SAL-ORD-2026-00036}, {@code MAT-DN-2026-00012}.</li>
     *   <li>Odoo uses a compact form for orders ({@code SO0042}) and <em>slash</em> segments for
     *       transfers ({@code WH/OUT/00042}).</li>
     * </ul>
     *
     * <p>Hence one separator class for both, and a multi-segment alternative: a pattern anchored on a
     * single segment captured just the middle of an ERPNext reference ({@code ORD-2026}), which matched
     * nothing downstream and read to the user as "this delivery does not exist".
     */
    private static final Pattern ENTITY_REF = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            + "|\\b[A-Za-z]{1,6}(?:[-/][A-Za-z0-9]+)*[-/]\\d{2,}"
            + "|\\b[A-Za-z]{1,5}\\d{3,}\\b)");

    /**
     * Matched against the <em>folded</em> question (see {@link #fold}), so every alternative here is
     * written unaccented: users type "etat" and "ou" as often as "état" and "où", and a lexicon that
     * only knows the accented spelling silently sends live questions to the corpus.
     */
    private static final Pattern LIVE_HINT = Pattern.compile(
            "(?i)\\b(statut|status|etat|etats|avancement|ou|ou est|position|localis|en cours|actuel|"
            + "actuelle|maintenant|en ce moment|aujourd'hui|aujourdhui|current|now|live|en retard|retard|"
            + "livree|livre|arrive|termine|reste|encore)\\b");

    private static final Pattern SLA_HINT = Pattern.compile(
            "(?i)\\b(sla|respect|respecte|viol|violation|en retard|depass|deadline|echeance|a temps)\\b");

    public Decision route(String query) {
        String raw = query == null ? "" : query;
        String q = fold(raw);
        Domain domain = detectDomain(q);
        // Matched on the raw text: an identifier is never accented, and folding would not change it,
        // but the reference must be returned exactly as the user wrote it for the live lookup.
        Matcher ref = ENTITY_REF.matcher(raw);
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

    /** {@code q} is already folded, so the alternatives are unaccented. */
    private Domain detectDomain(String q) {
        if (q.matches(".*\\b(retour|retours|rma|return)\\b.*")) return Domain.RMA;
        if (q.matches(".*\\b(tournee|tournees|itineraire|route)\\b.*")) return Domain.ROUTE;
        if (q.matches(".*\\b(livreur|livreurs|chauffeur|driver|conducteur)\\b.*")) return Domain.DRIVER;
        if (q.matches(".*\\b(livraison|livraisons|colis|commande|delivery|expedition)\\b.*")) return Domain.DELIVERY;
        return Domain.UNKNOWN;
    }

    /**
     * Lowercase and strip diacritics, so one spelling in the lexicons covers every way a user actually
     * types the word. Decomposing to NFD turns "é" into "e" + combining accent, which the following
     * range then removes.
     */
    public static String fold(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
