package com.asm.assistant.ingestion;

import com.asm.assistant.ingestion.model.SourceFile.SourceType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decides what participates in retrieval and at what authority — the guardrail that keeps noisy or
 * obsolete material out of the corpus.
 *
 * <p>The corpus root is mounted to the clean {@code docs/} tree, so the noisy repo artifacts
 * (root {@code context.md}, {@code .claude/**}, historical migration READMEs, secrets) are already
 * out of reach. This class is the second, explicit line of defence: it excludes anything that still
 * looks like noise even under the root, and assigns authority by area.
 *
 * <ul>
 *   <li><b>AUTHORITATIVE</b> — OpenAPI contracts (generated from the running services).</li>
 *   <li><b>SECONDARY</b> — architecture / ADR / business / ops docs (describe, don't define; code is
 *       the source of truth for SLA/RBAC/schema).</li>
 *   <li><b>EXCLUDED</b> — anything matching a noise pattern; never indexed.</li>
 * </ul>
 */
@Component
public class SourcePolicy {

    public static final String AUTHORITATIVE = "AUTHORITATIVE";
    public static final String SECONDARY = "SECONDARY";
    public static final String EXCLUDED = "EXCLUDED";

    /** Path fragments that must never be indexed even if they appear under the corpus root. */
    private static final List<String> EXCLUDE_FRAGMENTS = List.of(
            "context.md",        // stale root note — contradicts the code (single-tenant claim)
            "readme__",          // one-off historical migration notes
            "/.claude/",         // agent artifacts
            ".env"               // secrets
    );

    public boolean isExcluded(String relativePath) {
        String p = relativePath.toLowerCase().replace('\\', '/');
        return EXCLUDE_FRAGMENTS.stream().anyMatch(p::contains);
    }

    /** OpenAPI is the authoritative API contract; every other doc is secondary to the code. */
    public String authorityFor(String relativePath, SourceType type) {
        return type == SourceType.OPENAPI ? AUTHORITATIVE : SECONDARY;
    }
}
