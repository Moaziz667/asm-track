package com.asm.assistant.domain.port;

/**
 * The LLM behind an abstraction so the provider/model can change (or be mocked in tests) without
 * touching the orchestrator. One default adapter (Gemini) — not a multi-provider orchestration layer.
 */
public interface LlmPort {

    /**
     * Generate an answer.
     *
     * @param systemPrompt grounding/guardrail instructions (answer only from evidence, cite, refuse…)
     * @param userPrompt   the question plus the assembled, cited context
     * @return the model's text
     * @throws LlmUnavailableException on timeout, exhausted retries, or provider failure — the caller
     *         must return a controlled error, never a fabricated answer.
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * The same call, except the provider is told the reply must be a JSON object.
     *
     * <p>Used by the tool-selection step, whose answer is parsed rather than displayed. Asking
     * politely in the prompt is not enough: a model under load once replied with a long run of
     * {@code "!!!!!!"}, which no amount of parsing recovers, and the assistant silently lost its
     * access to live data. Providers exposing a JSON mode refuse to emit anything else.
     *
     * <p>Default: a plain completion, so an adapter with no JSON mode still works — the caller has to
     * tolerate a malformed reply in either case.
     */
    default String completeJson(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt);
    }

    /** Thrown when the model cannot be reached or fails; signals the fallback path. */
    class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
