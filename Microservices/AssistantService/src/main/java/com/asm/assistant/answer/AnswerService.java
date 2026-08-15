package com.asm.assistant.answer;

import com.asm.assistant.context.AssembledContext;
import com.asm.assistant.context.ContextAssembler;
import com.asm.assistant.domain.port.LlmPort;
import com.asm.assistant.retrieval.HybridRetriever;
import com.asm.assistant.retrieval.RetrievedChunk;
import com.asm.assistant.tools.LiveDataService;
import com.asm.assistant.tools.LiveDataService.LiveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The RAG orchestrator: route → (RAG | live | deterministic) → ground → answer, with refusal and
 * fallback as first-class outcomes.
 *
 * <ul>
 *   <li>RAG: retrieve → assemble cited context → LLM. No evidence ⇒ refuse (never guess).</li>
 *   <li>LIVE/DETERMINISTIC: read the live/deterministic source; if unreachable ⇒ say the state is
 *       unknown (never fabricate). SLA questions use the engine's sla-timeline, not the LLM.</li>
 *   <li>LLM unavailable ⇒ controlled degraded answer, never a hallucinated one.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnswerService {

    private final IntentRouter router;
    private final HybridRetriever retriever;
    private final ContextAssembler assembler;
    private final LlmPort llm;
    private final LiveDataService liveData;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnswerResponse answer(String question) {
        IntentRouter.Decision decision = router.route(question);
        log.debug("Routed '{}' -> {}", question, decision);
        return switch (decision.intent()) {
            case RAG -> answerFromRag(question);
            case LIVE_API, DETERMINISTIC -> answerFromLive(question, decision);
        };
    }

    private AnswerResponse answerFromRag(String question) {
        List<RetrievedChunk> ranked = retriever.retrieve(question);
        AssembledContext ctx = assembler.assemble(ranked);
        if (ctx.isEmpty()) {
            return AnswerResponse.refusal("RAG",
                    "Je n'ai pas assez d'éléments dans la documentation pour répondre.");
        }
        try {
            String text = llm.complete(GroundingPrompts.RAG_SYSTEM,
                    GroundingPrompts.ragUserPrompt(question, ctx.contextText()));
            return new AnswerResponse(text, "RAG", true, false, false, ctx.citations(), List.of());
        } catch (LlmPort.LlmUnavailableException e) {
            log.warn("LLM unavailable for RAG answer: {}", e.getMessage());
            return AnswerResponse.degraded("RAG",
                    "Le service de génération est momentanément indisponible. Réessayez plus tard.");
        }
    }

    private AnswerResponse answerFromLive(String question, IntentRouter.Decision decision) {
        String route = decision.intent().name();
        LiveResult live = liveData.fetch(decision);
        if (!live.available()) {
            return AnswerResponse.degraded(route,
                    "L'état actuel (" + live.sourceLabel() + ") est indisponible pour le moment.");
        }
        String json;
        try {
            json = mapper.writeValueAsString(live.data());
        } catch (Exception e) {
            json = String.valueOf(live.data());
        }
        try {
            String text = llm.complete(GroundingPrompts.LIVE_SYSTEM,
                    GroundingPrompts.liveUserPrompt(question, live.sourceLabel(), json));
            return new AnswerResponse(text, route, true, false, false, List.of(), List.of(live.sourceLabel()));
        } catch (LlmPort.LlmUnavailableException e) {
            return AnswerResponse.degraded(route,
                    "Le service de génération est momentanément indisponible. Réessayez plus tard.");
        }
    }
}
