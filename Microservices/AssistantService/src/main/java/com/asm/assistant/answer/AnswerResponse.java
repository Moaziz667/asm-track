package com.asm.assistant.answer;

import com.asm.assistant.context.AssembledContext.Citation;

import java.util.List;

/**
 * The assistant's answer plus provenance: which route produced it (RAG / live API / deterministic /
 * refusal), the citations backing a grounded answer, and any live sources consulted. {@code refused}
 * and {@code degraded} let the caller/UI distinguish "no evidence" and "service unavailable" from a
 * real answer — never a silent hallucination.
 */
public record AnswerResponse(
        String answer,
        String route,
        boolean grounded,
        boolean refused,
        boolean degraded,
        List<Citation> citations,
        List<String> liveSources
) {
    static AnswerResponse refusal(String route, String message) {
        return new AnswerResponse(message, route, false, true, false, List.of(), List.of());
    }

    static AnswerResponse degraded(String route, String message) {
        return new AnswerResponse(message, route, false, false, true, List.of(), List.of());
    }
}
