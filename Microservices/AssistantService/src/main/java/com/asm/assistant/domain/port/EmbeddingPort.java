package com.asm.assistant.domain.port;

import java.util.List;

/**
 * Turns text into vectors. One port, one default adapter (OpenAI) — the abstraction exists so the
 * provider and model can change without touching ingestion or retrieval, not to run several at once.
 */
public interface EmbeddingPort {

    /** @return one {@code float[dimension]} per input, in order; empty list if embedding is disabled. */
    List<float[]> embed(List<String> texts);

    /** Identifies which model produced a stored vector (persisted per chunk for re-embed safety). */
    String modelVersion();

    /** False when no API key is configured — ingestion then stores chunks without vectors. */
    boolean isEnabled();
}
