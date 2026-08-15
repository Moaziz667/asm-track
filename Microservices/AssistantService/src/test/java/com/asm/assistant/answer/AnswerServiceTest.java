package com.asm.assistant.answer;

import com.asm.assistant.context.AssembledContext;
import com.asm.assistant.context.AssembledContext.Citation;
import com.asm.assistant.context.ContextAssembler;
import com.asm.assistant.domain.port.LlmPort;
import com.asm.assistant.domain.port.LlmPort.LlmUnavailableException;
import com.asm.assistant.retrieval.HybridRetriever;
import com.asm.assistant.retrieval.RetrievedChunk;
import com.asm.assistant.tools.LiveDataService;
import com.asm.assistant.tools.LiveDataService.LiveResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    private final AnswerService service =
            new AnswerService(new IntentRouter(), retriever, assembler, llm, liveData);

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
        when(liveData.fetch(any())).thenReturn(new LiveResult(false, "Livraison D-1234", Map.of()));

        AnswerResponse r = service.answer("Quel est le statut actuel de la livraison D-1234 ?");

        assertThat(r.route()).isEqualTo("LIVE_API");
        assertThat(r.degraded()).isTrue();
        verifyNoInteractions(llm);
    }

    @Test
    void live_whenAvailable_answersFromLiveData() {
        when(liveData.fetch(any())).thenReturn(
                new LiveResult(true, "Livraison D-1234", Map.of("status", "IN_TRANSIT")));
        when(llm.complete(any(), any())).thenReturn("La livraison D-1234 est en transit.");

        AnswerResponse r = service.answer("Quel est le statut actuel de la livraison D-1234 ?");

        assertThat(r.route()).isEqualTo("LIVE_API");
        assertThat(r.grounded()).isTrue();
        assertThat(r.liveSources()).containsExactly("Livraison D-1234");
    }
}
