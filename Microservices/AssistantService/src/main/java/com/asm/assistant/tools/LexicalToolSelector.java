package com.asm.assistant.tools;

import com.asm.assistant.answer.IntentRouter;
import com.asm.assistant.tools.LiveToolCatalog.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Picks a live tool without calling the model — the standby for when {@link LiveToolSelector} cannot
 * reach a provider.
 *
 * <p>Why this exists: tool selection was a single point of failure on an external API. A rate-limited
 * or unavailable provider silently downgraded "combien de retours ?" into a documentation answer, which
 * then honestly refused — so an outage at the LLM vendor looked to the user like the platform having no
 * data at all.
 *
 * <p>Only tools that need no identifier are reachable here. Entity questions ("statut de la livraison
 * X") never depend on the model in the first place: {@link IntentRouter} sends them straight to the live
 * path. So this covers exactly the gap, and cannot invent an identifier.
 *
 * <p>Matching is deliberately blunt — a curated phrase list per tool, longest phrase wins. An ambiguous
 * question (two tools tied) yields nothing rather than a coin flip, because a wrong endpoint answered
 * confidently is worse than falling through to the documentation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LexicalToolSelector {

    private final LiveToolCatalog catalog;

    public Optional<Tool> select(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        // "comment fonctionne la synchronisation ERP ?" contains a trigger phrase but is a documentation
        // question. Phrase matching alone cannot tell the two apart, so the guard is applied here too.
        if (LiveToolSelector.isConceptual(question)) return Optional.empty();
        String q = IntentRouter.fold(question);

        Tool best = null;
        int bestScore = 0;
        boolean tied = false;

        for (Tool tool : catalog.all()) {
            if (tool.needsId()) continue;
            int score = scoreOf(tool, q);
            if (score == 0) continue;
            if (score > bestScore) {
                best = tool;
                bestScore = score;
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }

        if (best == null || tied) {
            if (tied) log.info("Lexical tool selection ambiguous for '{}', deferring to documentation", question);
            return Optional.empty();
        }
        log.info("Lexical tool selection chose {} (score={}) without the model", best.name(), bestScore);
        return Optional.of(best);
    }

    /**
     * The length of the longest trigger present in the question. Length, not count: "combien de
     * retours" must beat a tool that merely mentions "retours", and a longer phrase is by construction
     * the more specific match.
     */
    private int scoreOf(Tool tool, String foldedQuestion) {
        int best = 0;
        for (String trigger : tool.triggers()) {
            if (foldedQuestion.contains(trigger) && trigger.length() > best) {
                best = trigger.length();
            }
        }
        return best;
    }
}
