package com.asm.assistant.tools;

import com.asm.assistant.tools.LiveApiClient.LiveLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the reference a human uses into the UUID the live APIs require.
 *
 * <p>Nobody types {@code 83a201f9-3971-47a7-9c38-0ead88ef4413}. They type the ERP order reference
 * printed on their documents ({@code SAL-ORD-2026-00036}) or the customer's name. Without this step the
 * live lookup received the literal reference and the API answered {@code 400 Invalid value for
 * parameter 'id'}, which surfaced as "aucun élément ne correspond" — indistinguishable, for the user,
 * from the delivery not existing.
 *
 * <p>Resolution reuses the admin list search, so it inherits tenant isolation and RBAC unchanged: a
 * reference belonging to another tenant resolves to nothing, exactly as that tenant's list would show
 * nothing.
 *
 * <p><b>Ambiguity is not resolved by guessing.</b> A search matching several deliveries returns empty
 * rather than the first hit: answering confidently about the wrong delivery is worse than admitting the
 * reference was not specific enough.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReferenceResolver {

    private final LiveApiClient api;

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Outcome of a resolution attempt — the caller must be able to explain which case happened. */
    public enum Outcome { ALREADY_ID, RESOLVED, NO_MATCH, AMBIGUOUS, UNAVAILABLE }

    public record Resolution(Outcome outcome, String id, int matches) {
        public boolean usable() { return outcome == Outcome.ALREADY_ID || outcome == Outcome.RESOLVED; }
    }

    /**
     * Resolves a delivery reference. Only deliveries are searchable today; a route or RMA reference is
     * returned untouched, so those paths behave exactly as before.
     */
    public Resolution resolveDelivery(String reference) {
        if (reference == null || reference.isBlank()) {
            return new Resolution(Outcome.NO_MATCH, null, 0);
        }
        String ref = reference.trim();
        if (UUID_RE.matcher(ref).matches()) {
            return new Resolution(Outcome.ALREADY_ID, ref, 1);
        }

        LiveLookup search = api.searchDeliveries(ref);
        if (search.status() == LiveApiClient.Status.UNAVAILABLE) {
            return new Resolution(Outcome.UNAVAILABLE, null, 0);
        }

        List<Map<String, Object>> hits = contentOf(search.body());
        if (hits.isEmpty()) {
            log.info("Reference '{}' matched no delivery", ref);
            return new Resolution(Outcome.NO_MATCH, null, 0);
        }

        // The search is a substring match, so an order's reshipments and backorders come back with it:
        // "SAL-ORD-2026-00031" also matches "SAL-ORD-2026-00031#R2". Asking the user to disambiguate
        // the reference they typed exactly would be absurd, so an exact hit wins over its derivatives.
        List<Map<String, Object>> exact = hits.stream().filter(h -> matchesExactly(h, ref)).toList();
        if (exact.size() == 1) {
            hits = exact;
        } else if (hits.size() > 1) {
            log.info("Reference '{}' matched {} deliveries ({} exact) — refusing to guess",
                    ref, hits.size(), exact.size());
            return new Resolution(Outcome.AMBIGUOUS, null, hits.size());
        }

        Object id = deliveryIdOf(hits.get(0));
        if (id == null) {
            // A row without the identifier means the search contract changed under us — loud, because
            // silently reporting "no such delivery" for a delivery that exists is indistinguishable
            // from a wrong reference, and sent the previous debugging session the wrong way.
            log.error("Delivery search row for '{}' carries no identifier — keys: {}", ref, hits.get(0).keySet());
            return new Resolution(Outcome.NO_MATCH, null, 0);
        }
        log.info("Resolved reference '{}' to delivery {}", ref, id);
        return new Resolution(Outcome.RESOLVED, id.toString(), 1);
    }

    /**
     * The delivery's own identifier in a search row. The admin summary calls it {@code deliveryId} —
     * {@code id} there would be ambiguous next to {@code orderId} — so reading a plain {@code id}
     * silently yielded nothing and every reference looked non-existent. {@code id} is still accepted as
     * a fallback so a leaner payload keeps working.
     */
    private Object deliveryIdOf(Map<String, Object> row) {
        Object id = row.get("deliveryId");
        return id != null ? id : row.get("id");
    }

    /**
     * Whether a row carries this exact reference. Three fields can hold it: {@code orderRef} (what the
     * UI shows), {@code erpOrderId} (Odoo/ERPNext order id) and {@code erpExternalRef} (the backorder
     * form, {@code S00123/BO}). Which one is populated depends on the ERP and on whether the delivery
     * is a backorder, so all three are compared rather than assuming one.
     */
    private boolean matchesExactly(Map<String, Object> row, String ref) {
        for (String field : List.of("orderRef", "erpOrderId", "erpExternalRef")) {
            Object value = row.get(field);
            if (value != null && ref.equalsIgnoreCase(value.toString().trim())) {
                return true;
            }
        }
        return false;
    }

    /** Spring serialises a {@code Page} with its rows under {@code content}. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentOf(Map<String, Object> body) {
        if (body == null) return List.of();
        Object content = body.get("content");
        return content instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
