package com.asm.assistant.tools;

import com.asm.assistant.tools.LiveApiClient.LiveLookup;
import com.asm.assistant.tools.LiveApiClient.Status;
import com.asm.assistant.tools.ReferenceResolver.Outcome;
import com.asm.assistant.tools.ReferenceResolver.Resolution;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Reference → UUID. The resolver never parses the reference itself: it hands the string to the admin
 * search, which is what makes it work for Odoo and ERPNext alike without knowing either format.
 */
class ReferenceResolverTest {

    private final LiveApiClient api = Mockito.mock(LiveApiClient.class);
    private final ReferenceResolver resolver = new ReferenceResolver(api);

    /**
     * Rows use {@code deliveryId}, which is what {@code AdminDeliverySummaryResponse} actually
     * serialises. An earlier version of these fixtures invented an {@code id} field: every test passed
     * while production resolved nothing, because the fixture agreed with the code instead of with the
     * API. Any change here must be checked against that DTO, not against the resolver.
     */
    private static LiveLookup page(String... ids) {
        List<Map<String, Object>> rows = java.util.Arrays.stream(ids)
                .map(id -> Map.<String, Object>of("deliveryId", id))
                .toList();
        return LiveLookup.found(Map.of("content", rows, "totalElements", rows.size()));
    }

    /** A page whose rows carry an ERP reference, as the admin search really answers. */
    private static LiveLookup pageOf(Map<String, String> idToRef) {
        List<Map<String, Object>> rows = idToRef.entrySet().stream()
                .map(e -> Map.<String, Object>of("deliveryId", e.getKey(), "erpOrderId", e.getValue()))
                .toList();
        return LiveLookup.found(Map.of("content", rows, "totalElements", rows.size()));
    }

    @Test
    void aUuidIsUsedAsIs_withoutASearch() {
        Resolution r = resolver.resolveDelivery("83a201f9-3971-47a7-9c38-0ead88ef4413");
        assertThat(r.outcome()).isEqualTo(Outcome.ALREADY_ID);
        assertThat(r.id()).isEqualTo("83a201f9-3971-47a7-9c38-0ead88ef4413");
        Mockito.verifyNoInteractions(api);
    }

    /** The whole point: the user types what the ERP printed, whichever ERP that is. */
    @Test
    void erpReferencesResolveToTheDeliveryId() {
        when(api.searchDeliveries(anyString())).thenReturn(page("11111111-1111-1111-1111-111111111111"));

        for (String ref : List.of("SAL-ORD-2026-00036", "WH/OUT/00042", "SO0042", "Hotel Riadh Palms")) {
            Resolution r = resolver.resolveDelivery(ref);
            assertThat(r.usable()).as("resolving %s", ref).isTrue();
            assertThat(r.id()).isEqualTo("11111111-1111-1111-1111-111111111111");
        }
    }

    /** Answering confidently about the wrong delivery is worse than asking the user to be precise. */
    @Test
    void severalMatches_refuseToGuess() {
        when(api.searchDeliveries(anyString())).thenReturn(
                page("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"));

        Resolution r = resolver.resolveDelivery("Hotel Riadh Palms");
        assertThat(r.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(r.matches()).isEqualTo(2);
        assertThat(r.usable()).isFalse();
    }

    @Test
    void noMatchIsAFact_butAnOutageIsNot() {
        when(api.searchDeliveries(anyString())).thenReturn(page());
        assertThat(resolver.resolveDelivery("SAL-ORD-9999-99999").outcome()).isEqualTo(Outcome.NO_MATCH);

        when(api.searchDeliveries(anyString())).thenReturn(new LiveLookup(Status.UNAVAILABLE, Map.of()));
        assertThat(resolver.resolveDelivery("SAL-ORD-2026-00036").outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    /**
     * Real case from the field: the substring search returns the order together with its reshipments
     * ("…00031" and "…00031#R2"). The reference the user typed exists exactly, so it must win instead
     * of asking them to disambiguate what they already spelled out.
     */
    @Test
    void exactReferenceWins_overItsReshipments() {
        when(api.searchDeliveries(anyString())).thenReturn(pageOf(new java.util.LinkedHashMap<>(Map.of()) {{
            put("aaaaaaaa-1111-1111-1111-111111111111", "SAL-ORD-2026-00031");
            put("bbbbbbbb-2222-2222-2222-222222222222", "SAL-ORD-2026-00031#R2");
            put("cccccccc-3333-3333-3333-333333333333", "SAL-ORD-2026-00031#R2");
        }}));

        Resolution r = resolver.resolveDelivery("SAL-ORD-2026-00031");
        assertThat(r.outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(r.id()).isEqualTo("aaaaaaaa-1111-1111-1111-111111111111");
    }

    /** Without an exact hit there is nothing to prefer, so the refusal stands. */
    @Test
    void severalDerivatives_andNoExactHit_stayAmbiguous() {
        when(api.searchDeliveries(anyString())).thenReturn(pageOf(new java.util.LinkedHashMap<>(Map.of()) {{
            put("bbbbbbbb-2222-2222-2222-222222222222", "SAL-ORD-2026-00031#R2");
            put("cccccccc-3333-3333-3333-333333333333", "SAL-ORD-2026-00031#R3");
        }}));

        assertThat(resolver.resolveDelivery("SAL-ORD-2026-00031").outcome()).isEqualTo(Outcome.AMBIGUOUS);
    }

    /** A row with neither identifier is a contract break, not "no such delivery". */
    @Test
    void rowWithoutAnIdentifier_doesNotResolve() {
        when(api.searchDeliveries(anyString())).thenReturn(LiveLookup.found(Map.of(
                "content", List.of(Map.of("erpOrderId", "SAL-ORD-2026-00036")))));

        assertThat(resolver.resolveDelivery("SAL-ORD-2026-00036").usable()).isFalse();
    }

    /** A leaner payload that names the key {@code id} must keep working. */
    @Test
    void plainIdFieldIsAcceptedAsFallback() {
        when(api.searchDeliveries(anyString())).thenReturn(LiveLookup.found(Map.of(
                "content", List.of(Map.of("id", "44444444-4444-4444-4444-444444444444")))));

        Resolution r = resolver.resolveDelivery("SAL-ORD-2026-00036");
        assertThat(r.outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(r.id()).isEqualTo("44444444-4444-4444-4444-444444444444");
    }

    @Test
    void blankReferenceResolvesToNothing() {
        assertThat(resolver.resolveDelivery(null).usable()).isFalse();
        assertThat(resolver.resolveDelivery("  ").usable()).isFalse();
    }
}
