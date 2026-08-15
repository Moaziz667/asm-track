package com.asm.assistant.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal, enterprise-appropriate prompt-injection defence — not an AI-security framework.
 *
 * <p>The primary defence is architectural: the grounding system prompt tells the model that context
 * and live payloads are DATA, and the model only answers from provided evidence. This guard adds two
 * cheap, high-value things on top:
 * <ul>
 *   <li>{@link #inspect(String)} flags a user query that looks like an override/exfiltration attempt
 *       ("ignore previous instructions", "reveal your system prompt", French equivalents…), so it can
 *       be audited and counted — without blocking legitimate questions (flag, don't refuse).</li>
 *   <li>{@link #fence(String)} wraps retrieved/untrusted text in explicit delimiters so instructions
 *       embedded in a document can't be mistaken for the operator's system prompt.</li>
 * </ul>
 */
@Component
public class PromptInjectionGuard {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions|prompts)"),
            Pattern.compile("(?i)disregard\\s+(the\\s+)?(above|previous|system)"),
            Pattern.compile("(?i)(reveal|print|show|repeat)\\s+(your|the)\\s+(system\\s+prompt|instructions|prompt)"),
            Pattern.compile("(?i)you\\s+are\\s+now\\b"),
            Pattern.compile("(?i)\\b(developer|god|jailbreak)\\s+mode\\b"),
            // French
            Pattern.compile("(?i)ignore[sz]?\\s+(les\\s+)?(instructions|consignes)\\s+(pr[ée]c[ée]dentes|ci-dessus)?"),
            Pattern.compile("(?i)oublie[sz]?\\s+(tes|les)\\s+(instructions|consignes)"),
            Pattern.compile("(?i)(r[ée]v[èe]le|affiche|montre)\\s+(ton|le)\\s+(prompt|syst[èe]me|instructions)")
    );

    public record Inspection(boolean flagged, String reason) {
        static Inspection clean() { return new Inspection(false, null); }
    }

    /** Flag (do not block) queries that look like injection/exfiltration attempts. */
    public Inspection inspect(String query) {
        if (query == null) return Inspection.clean();
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(query).find()) {
                return new Inspection(true, "matched injection pattern");
            }
        }
        return Inspection.clean();
    }

    /** Wrap untrusted text so embedded instructions are visibly data, not system directives. */
    public String fence(String untrusted) {
        return "<<<DONNÉES_NON_FIABLES>>>\n" + untrusted + "\n<<<FIN_DONNÉES>>>";
    }
}
