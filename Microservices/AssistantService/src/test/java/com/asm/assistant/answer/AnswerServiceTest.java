package com.asm.assistant.answer;

import com.asm.assistant.context.AssembledContext;
import com.asm.assistant.context.AssembledContext.Citation;
import com.asm.assistant.context.ContextAssembler;
import com.asm.assistant.domain.port.LlmPort;
import com.asm.assistant.domain.port.LlmPort.LlmUnavailableException;
import com.asm.assistant.retrieval.HybridRetriever;
import com.asm.assistant.retrieval.RetrievedChunk;
import com.asm.assistant.tools.LiveDataService;
import com.asm.assistant.tools.LiveApiClient.Status;
import com.asm.assistant.tools.LiveDataService.LiveResult;
import com.asm.assistant.tools.LiveToolCatalog;
import com.asm.assistant.tools.LiveToolSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Orchestrator behaviour with stubbed collaborators — grounding, refusal and fallback are all
 * first-class outcomes and none of them fabricates an answer. No Spring, no network.
 */
class AnswerServiceTest {

    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final ContextAssembler assembler = mock(ContextAssembler.class);
    private final LlmPort llm = mock(LlmPort.class);
    private final LiveDataService liveData = mock(LiveDataService.class);
    private final LiveToolSelector toolSelector = mock(LiveToolSelector.class);
    private final AnswerService service =
            new AnswerService(new IntentRouter(), retriever, assembler, llm, liveData, toolSelector);

    {
        // Default: no live tool fits, so the documentation path is exercised unless a test says otherwise.
        when(toolSelector.select(any())).thenReturn(Optional.empty());
    }

    private AssembledContext withEvidence() {
        return new AssembledContext("[1] règle SLA",
                List.of(new Citation(1, UUID.randomUUID(), "metier/livraison.md", "SLA", "SECONDARY")));
    }

    @Test
    void rag_withEvidence_isGroundedAndCited() {
        when(retriever.retrieve(any())).thenReturn(List.of(mock(RetrievedChunk.class)));
        when(assembler.assemble(any())).thenReturn(withEvidence());
        when(llm.complete(any(), any())).thenReturn("Le SLA comporte plusieurs phases [1].");

        AnswerResponse r = service.answer("Quelles sont les phases du SLA ?");

        assertThat(r.route()).isEqualTo("RAG");
        assertThat(r.grounded()).isTrue();
        assertThat(r.refused()).isFalse();
        assertThat(r.citations()).hasSize(1);
    }

    @Test
    void rag_withNoEvidence_refuses_withoutCallingLlm() {
        when(retriever.retrieve(any())).thenReturn(List.of());
        when(assembler.assemble(any())).thenReturn(new AssembledContext("", List.of()));

        AnswerResponse r = service.answer("Comment fonctionne le RMA ?");

        assertThat(r.refused()).isTrue();
        assertThat(r.grounded()).isFalse();
        verifyNoInteractions(llm);
    }

    @Test
    void rag_whenLlmDown_degradesInsteadOfHallucinating() {
        when(retriever.retrieve(any())).thenReturn(List.of(mock(RetrievedChunk.class)));
        when(assembler.assemble(any())).thenReturn(withEvidence());
        when(llm.complete(any(), any())).thenThrow(new LlmUnavailableException("down", null));

        AnswerResponse r = service.answer("Quelles sont les phases du SLA ?");

        assertThat(r.degraded()).isTrue();
        assertThat(r.grounded()).isFalse();
    }

    @Test
    void live_whenApiUnavailable_doesNotFabricateState() {
        when(liveData.fetch(any())).thenReturn(
                new LiveResult(Status.UNAVAILABLE, "Livraison D-1234", Map.of()));

        AnswerResponse r = service.answer("Quel est le statut actuel de la livraison D-1234 ?");

        assertThat(r.route()).isEqualTo("LIVE_API");
        assertThat(r.degraded()).isTrue();
        verifyNoInteractions(llm);
    }

    /** A missing entity is a factual answer, not an outage — it must not be reported as degraded. */
    @Test
    void live_whenEntityMissing_refusesRatherThanBlamingTheService() {
        when(liveData.fetch(any())).thenReturn(
                new LiveResult(Status.NOT_FOUND, "Livraison D-9999", Map.of()));

        AnswerResponse r = service.answer("Quel est le statut actuel de la livraison D-9999 ?");

        assertThat(r.route()).isEqualTo("LIVE_API");
        assertThat(r.refused()).isTrue();
        assertThat(r.degraded()).isFalse();
        verifyNoInteractions(llm);
    }

    /** "les dépôts ?" carries no identifier, so only the tool catalogue can reach the real data. */
    @Test
    void tool_whenSelected_answersFromTenantDataNotTheCorpus() {
        LiveToolCatalog.Tool depots = new LiveToolCatalog().byName("depots_active").orElseThrow();
        when(toolSelector.select(any())).thenReturn(
                Optional.of(new LiveToolSelector.Selection(depots, null)));
        when(liveData.run(any(), any())).thenReturn(
                new LiveResult(Status.FOUND, "Dépôts actifs", Map.of("items", List.of(Map.of("name", "Tunis")))));
        when(llm.complete(any(), any())).thenReturn("Vous avez un dépôt actif : Tunis.");

        AnswerResponse r = service.answer("les dépôts ?");

        assertThat(r.route()).isEqualTo("LIVE_API");
        assertThat(r.liveSources()).containsExactly("Dépôts actifs");
        verifyNoInteractions(retriever);
    }

    /** A tool that cannot answer must not sink the question — the documentation is still worth trying. */
    @Test
    void tool_whenUnavailable_fallsBackToDocumentation() {
        LiveToolCatalog.Tool depots = new LiveToolCatalog().byName("depots_active").orElseThrow();
        when(toolSelector.select(any())).thenReturn(
                Optional.of(new LiveToolSelector.Selection(depots, null)));
        when(liveData.run(any(), any())).thenReturn(
                new LiveResult(Status.UNAVAILABLE, "Dépôts actifs", Map.of()));
        when(retriever.retrieve(any())).thenReturn(List.of(mock(RetrievedChunk.class)));
        when(assembler.assemble(any())).thenReturn(withEvidence());
        when(llm.complete(any(), any())).thenReturn("Un dépôt est un entrepôt source [1].");

        AnswerResponse r = service.answer("les dépôts ?");

        assertThat(r.route()).isEqualTo("RAG");
        assertThat(r.grounded()).isTrue();
    }

    @Test
    void live_whenAvailable_answersFromLiveData() {
        when(liveData.fetch(any())).thenReturn(
                new LiveResult(Status.FOUND, "Livraison D-1234", Map.of("status", "IN_TRANSIT")));
        when(llm.complete(any(), any())).thenReturn("La livraison D-1234 est en transit.");

        AnswerResponse r = service.answer("Quel est le statut actuel de la livraison D-1234 ?");

        assertThat(r.route()).isEqualTo("LIVE_API");
        assertThat(r.grounded()).isTrue();
        assertThat(r.liveSources()).containsExactly("Livraison D-1234");
    }
}
