package com.asm.assistant.answer;

import com.asm.assistant.context.AssembledContext;
import com.asm.assistant.context.ContextAssembler;
import com.asm.assistant.domain.port.LlmPort;
import com.asm.assistant.retrieval.HybridRetriever;
import com.asm.assistant.retrieval.RetrievedChunk;
import com.asm.assistant.tools.LiveDataService;
import com.asm.assistant.tools.LiveDataService.LiveResult;
import com.asm.assistant.tools.LiveToolSelector;
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
    private final LiveToolSelector toolSelector;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnswerResponse answer(String question) {
        IntentRouter.Decision decision = router.route(question);
        log.debug("Routed '{}' -> {}", question, decision);
        return switch (decision.intent()) {
            case RAG -> answerAboutTenantDataOrDocs(question);
            case LIVE_API, DETERMINISTIC -> answerFromLive(question, decision);
        };
    }

    /**
     * Questions the rule-based router could not tie to one entity. Most are documentation questions,
     * but some ask about this tenant's own data ("les dépôts ?", "combien de livraisons aujourd'hui ?")
     * — no identifier to match on, yet no answer in the corpus either. So the model is offered the
     * read-only tool catalogue first; it declines for anything conceptual, and we fall through to RAG.
     */
    private AnswerResponse answerAboutTenantDataOrDocs(String question) {
        return toolSelector.select(question)
                .map(sel -> answerFromTool(question, sel))
                .orElseGet(() -> answerFromRag(question));
    }

    private AnswerResponse answerFromTool(String question, LiveToolSelector.Selection sel) {
        log.debug("Live tool selected: {} (id={})", sel.tool().name(), sel.entityId());
        LiveResult live = liveData.run(sel.tool(), sel.entityId());
        if (live.notFound()) {
            return AnswerResponse.refusal("LIVE_API",
                    "Aucun élément ne correspond à cette référence (" + live.sourceLabel() + ").");
        }
        // A tool that cannot answer is not a dead end: the documentation may still cover the question.
        if (!live.available()) {
            log.info("Live tool {} unavailable, falling back to documentation", sel.tool().name());
            return answerFromRag(question);
        }
        return generateFromLive(question, live, "LIVE_API");
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
        // "No such entity" is an answer, not an outage — saying "service indisponible" would blame
        // the platform for what is really a wrong reference.
        if (live.notFound()) {
            return AnswerResponse.refusal(route, notFoundMessage(live));
        }
        if (!live.available()) {
            return AnswerResponse.degraded(route,
                    "L'état actuel (" + live.sourceLabel() + ") est indisponible pour le moment.");
        }
        return generateFromLive(question, live, route);
    }

    /**
     * A resolver that found several candidates has something specific to say; anything else falls back
     * to the generic wording. Concatenating the two produced "aucun élément ne correspond … 3 livraisons
     * correspondent", which contradicts itself.
     */
    private String notFoundMessage(LiveResult live) {
        return live.hasMessage()
                ? live.message()
                : "Aucun élément ne correspond à cette référence (" + live.sourceLabel() + ").";
    }

    private AnswerResponse generateFromLive(String question, LiveResult live, String route) {
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
