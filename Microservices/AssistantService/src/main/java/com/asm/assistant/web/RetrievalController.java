package com.asm.assistant.web;

import com.asm.assistant.context.AssembledContext;
import com.asm.assistant.context.ContextAssembler;
import com.asm.assistant.retrieval.HybridRetriever;
import com.asm.assistant.retrieval.RetrievedChunk;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Retrieval-only endpoint: hybrid search + assembled, cited context. It exists so retrieval can be
 * evaluated and inspected independently of the LLM (Phase 4 adds the grounded-answer endpoint on top
 * of exactly this output). Tenant-scoped and operator-gated like the rest of the surface.
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class RetrievalController {

    private final HybridRetriever retriever;
    private final ContextAssembler assembler;

    public record SearchRequest(@NotBlank String query) {}

    public record Hit(String path, String section, String authority, double fusedScore, String preview) {}

    public record SearchResponse(String query, int hits, List<Hit> results,
                                 List<AssembledContext.Citation> citations, String context) {}

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest req) {
        List<RetrievedChunk> ranked = retriever.retrieve(req.query());
        AssembledContext ctx = assembler.assemble(ranked);
        List<Hit> hits = ranked.stream()
                .map(c -> new Hit(c.path(), c.section(), c.authority(), c.fusedScore(), preview(c.content())))
                .toList();
        return new SearchResponse(req.query(), hits.size(), hits, ctx.citations(), ctx.contextText());
    }

    private String preview(String content) {
        String s = content.strip().replaceAll("\\s+", " ");
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
