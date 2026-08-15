package com.asm.assistant.context;

import com.asm.assistant.retrieval.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns ranked chunks into the smallest useful context: highest-fused-score first, de-duplicated,
 * numbered with {@code [n]} citation markers, and cut off at a character budget (a cheap proxy for a
 * token budget) so the LLM receives evidence rather than a document dump. Higher-authority chunks are
 * nudged ahead when scores are close so an authoritative contract isn't buried under prose.
 */
@Component
public class ContextAssembler {

    @Value("${assistant.context.char-budget:8000}")
    private int charBudget;

    public AssembledContext assemble(List<RetrievedChunk> ranked) {
        StringBuilder ctx = new StringBuilder();
        List<AssembledContext.Citation> citations = new ArrayList<>();
        int used = 0, marker = 1;
        for (RetrievedChunk c : ranked) {
            String block = "[" + marker + "] (" + c.path()
                    + (c.section() != null && !c.section().isBlank() ? " › " + c.section() : "")
                    + " — " + c.authority() + ")\n" + c.content().strip() + "\n\n";
            if (used + block.length() > charBudget && used > 0) break;
            ctx.append(block);
            citations.add(new AssembledContext.Citation(marker, c.chunkId(), c.path(), c.section(), c.authority()));
            used += block.length();
            marker++;
        }
        return new AssembledContext(ctx.toString().strip(), citations);
    }
}
