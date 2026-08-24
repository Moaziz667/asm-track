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

    /**
     * The reported bug: "etat" is how the question is actually typed, and the accented-only lexicon
     * sent it to the corpus, which answered "pas assez d'éléments" for a live question.
     */
    @Test
    void unaccentedSpellings_routeLikeTheirAccentedForm() {
        assertThat(router.route("etat de la livraison D-1234").intent()).isEqualTo(Intent.LIVE_API);
        assertThat(router.route("état de la livraison D-1234").intent()).isEqualTo(Intent.LIVE_API);
        assertThat(router.route("ou est la tournee T-9001").intent()).isEqualTo(Intent.LIVE_API);
        assertThat(router.route("ou est la tournée T-9001").domain()).isEqualTo(Domain.ROUTE);
    }

    /** The identifier is echoed back to the live lookup, so folding must not touch it. */
    @Test
    void entityIdKeepsItsOriginalSpelling() {
        assertThat(router.route("Etat de la livraison ABC-4321 ?").entityId()).isEqualTo("ABC-4321");
    }

    /**
     * The reference users actually paste. A single-segment pattern captured "ORD-2026" out of the
     * middle, which matched nothing downstream and read as "no such delivery".
     */
    @Test
    void erpNextReference_isCapturedWhole() {
        IntentRouter.Decision d = router.route("etat du SAL-ORD-2026-00036");
        assertThat(d.entityId()).isEqualTo("SAL-ORD-2026-00036");
        assertThat(d.intent()).isEqualTo(Intent.LIVE_API);
        assertThat(router.route("statut de MAT-DN-2026-00012").entityId()).isEqualTo("MAT-DN-2026-00012");
    }

    /** Odoo names transfers with slashes and orders compactly — neither looks like an ERPNext code. */
    @Test
    void odooReferences_areCapturedToo() {
        assertThat(router.route("etat du transfert WH/OUT/00042").entityId()).isEqualTo("WH/OUT/00042");
        assertThat(router.route("statut de la commande SO0042").entityId()).isEqualTo("SO0042");
    }

    @Test
    void shortInternalReferences_stillWork() {
        assertThat(router.route("statut de D-1234").entityId()).isEqualTo("D-1234");
        assertThat(router.route("ou en est RMA1234").entityId()).isEqualTo("RMA1234");
    }

    @Test
    void slaComplianceOfAnEntity_isDeterministic() {
        IntentRouter.Decision d = router.route("La livraison 123e4567-e89b-12d3-a456-426614174000 respecte-t-elle le SLA ?");
        assertThat(d.intent()).isEqualTo(Intent.DETERMINISTIC);
        assertThat(d.entityId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
    }
}
