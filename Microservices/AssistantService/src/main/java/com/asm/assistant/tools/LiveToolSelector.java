package com.asm.assistant.tools;

import com.asm.assistant.answer.GroundingPrompts;
import com.asm.assistant.domain.port.LlmPort;
import com.asm.assistant.tools.LiveToolCatalog.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks which live read (if any) answers the question, by asking the model to name one tool from
 * {@link LiveToolCatalog}.
 *
 * <p>Deliberately <em>not</em> the providers' native function-calling API: that format differs between
 * OpenAI-compatible gateways and Gemini, and the point of {@code LlmPort} is that either can answer.
 * Asking for a small JSON object works identically on both, and on a local model later.
 *
 * <p>The selection is advisory and fail-open: an unparseable reply, an unknown tool name, or an
 * unreachable model all mean "no tool", and the caller falls back to the documentation. The model can
 * only return a name from the catalogue, so a bad choice reads the wrong endpoint — it can never reach
 * an undeclared one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveToolSelector {

    private final LlmPort llm;
    private final LiveToolCatalog catalog;
    private final ObjectMapper mapper = new ObjectMapper();

    /** A chosen tool plus the entity reference the model extracted, when the tool needs one. */
    public record Selection(Tool tool, String entityId) {}

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);

    /**
     * Questions asking how or why something works. Selection costs a full LLM round-trip, and for
     * these the model reliably answers "no tool" anyway — so the call is pure latency and quota. A
     * false positive here only means the corpus answers a data question, which retrieval then refuses.
     */
    private static final Pattern CONCEPTUAL = Pattern.compile(
            "(?i)\\b(comment|pourquoi|qu'est[- ]ce|c'est quoi|explique|expliquer|définit|définition|"
            + "signifie|fonctionne|fonctionnement|règle|règles|procédure|principe|architecture|"
            + "différence|how|why|what is)\\b");

    /**
     * A degenerate reply ("!!!!!!…" observed in practice) is not an error the transport can see, so it
     * never reaches the adapter's own retry. One extra attempt covers both that and a provider blip;
     * beyond that the documentation answers, which is the honest outcome anyway.
     */
    private static final int SELECTION_ATTEMPTS = 2;

    public Optional<Selection> select(String question) {
        if (question == null || CONCEPTUAL.matcher(question).find()) {
            return Optional.empty();
        }

        JsonNode node = null;
        for (int attempt = 1; attempt <= SELECTION_ATTEMPTS && node == null; attempt++) {
            String raw;
            try {
                raw = llm.completeJson(GroundingPrompts.TOOL_SELECT_SYSTEM,
                        GroundingPrompts.toolSelectUserPrompt(question, catalog.asPromptCatalogue()));
            } catch (LlmPort.LlmUnavailableException e) {
                log.warn("Tool selection unavailable, falling back to documentation: {}", e.getMessage());
                return Optional.empty();
            }
            node = parse(raw);
            if (node == null) {
                log.info("Tool selection returned no usable JSON (attempt {}/{}): {}",
                        attempt, SELECTION_ATTEMPTS, abbreviate(raw));
            }
        }
        if (node == null) return Optional.empty();

        Optional<Tool> tool = catalog.byName(node.path("tool").asText(null));
        if (tool.isEmpty()) return Optional.empty();

        String id = node.path("id").asText(null);
        if (id != null && (id.isBlank() || "null".equalsIgnoreCase(id))) id = null;
        if (tool.get().needsId() && id == null) {
            // The model wants an entity read but found no reference — the documentation is the
            // honest fallback, guessing an identifier is not.
            log.info("Tool {} needs a reference but none was extracted", tool.get().name());
            return Optional.empty();
        }
        return Optional.of(new Selection(tool.get(), id));
    }

    /** Models wrap JSON in prose or fences; take the first object-looking span and try that. */
    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        try {
            return mapper.readTree(cleaned);
        } catch (Exception ignored) {
            Matcher m = JSON_OBJECT.matcher(cleaned);
            while (m.find()) {
                try {
                    return mapper.readTree(m.group());
                } catch (Exception ignored2) {
                    // keep scanning
                }
            }
            return null;
        }
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
