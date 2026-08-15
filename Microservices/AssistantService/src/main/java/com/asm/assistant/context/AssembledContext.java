package com.asm.assistant.context;

import java.util.List;
import java.util.UUID;

/**
 * The bounded context handed to the LLM (Phase 4), plus the citations that back it. {@code contextText}
 * carries {@code [n]} markers the model must cite; {@code citations} maps each marker to its source.
 */
public record AssembledContext(String contextText, List<Citation> citations) {

    public record Citation(int marker, UUID chunkId, String path, String section, String authority) {}

    public boolean isEmpty() {
        return citations.isEmpty();
    }
}
