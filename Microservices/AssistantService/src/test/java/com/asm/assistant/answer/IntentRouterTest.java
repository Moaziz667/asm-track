package com.asm.assistant.answer;

import com.asm.assistant.answer.IntentRouter.Domain;
import com.asm.assistant.answer.IntentRouter.Intent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure routing logic — no Spring, no network. Covers every domain incl. RMA. */
class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    @Test
    void knowledgeQuestions_goToRag() {
        assertThat(router.route("Quelles sont les phases du SLA ?").intent()).isEqualTo(Intent.RAG);
        assertThat(router.route("Comment fonctionne la synchronisation ERP ?").intent()).isEqualTo(Intent.RAG);
        assertThat(router.route("Comment sont gérés les retours RMA ?").intent()).isEqualTo(Intent.RAG);
    }

    @Test
    void currentStateOfAnEntity_goesLive() {
        IntentRouter.Decision d = router.route("Quel est le statut actuel de la livraison D-1234 ?");
        assertThat(d.intent()).isEqualTo(Intent.LIVE_API);
        assertThat(d.domain()).isEqualTo(Domain.DELIVERY);
        assertThat(d.entityId()).isEqualTo("D-1234");
    }

    @Test
    void rmaAndRoute_domainsAreDetected() {
        assertThat(router.route("Où en est le retour RMA1234 en ce moment ?").domain()).isEqualTo(Domain.RMA);
        assertThat(router.route("Où est le livreur de la tournée T-9001 maintenant ?").domain())
                .isIn(Domain.ROUTE, Domain.DRIVER);
    }

    @Test
    void slaComplianceOfAnEntity_isDeterministic() {
        IntentRouter.Decision d = router.route("La livraison 123e4567-e89b-12d3-a456-426614174000 respecte-t-elle le SLA ?");
        assertThat(d.intent()).isEqualTo(Intent.DETERMINISTIC);
        assertThat(d.entityId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
    }
}
