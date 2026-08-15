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

    /** Thrown when the model cannot be reached or fails; signals the fallback path. */
    class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
