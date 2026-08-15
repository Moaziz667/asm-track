package com.asm.assistant.adapter.llm;

import com.asm.assistant.domain.port.LlmPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The {@link LlmPort} the orchestrator actually gets: the configured provider first, the other one as
 * a standby. A free-tier provider answering 429/503 is the normal case, not the exception — losing the
 * whole assistant to one saturated quota is avoidable, so a provider failure falls through instead of
 * degrading straight away.
 *
 * <p>Order comes from {@code assistant.llm.provider} ({@code openai} → OpenAI-compatible then Gemini,
 * anything else → Gemini then OpenAI-compatible). Unconfigured providers are skipped, so a single-key
 * deployment behaves exactly as before. When every provider fails the last error is rethrown, and
 * {@code AnswerService} returns its controlled degraded answer — never a fabricated one.
 */
@Component
@Primary
@Slf4j
public class FailoverLlmAdapter implements LlmPort {

    private final List<LlmPort> providers;
    private final List<String> names;

    public FailoverLlmAdapter(
            @Value("${assistant.llm.provider:gemini}") String provider,
            GeminiLlmAdapter gemini,
            OpenAiCompatibleLlmAdapter openAi) {

        boolean openAiFirst = "openai".equalsIgnoreCase(provider) || "openrouter".equalsIgnoreCase(provider);
        record Candidate(String name, LlmPort port, boolean configured) {}
        List<Candidate> ordered = openAiFirst
                ? List.of(new Candidate("openai-compatible", openAi, openAi.isConfigured()),
                          new Candidate("gemini", gemini, gemini.isConfigured()))
                : List.of(new Candidate("gemini", gemini, gemini.isConfigured()),
                          new Candidate("openai-compatible", openAi, openAi.isConfigured()));

        List<Candidate> usable = ordered.stream().filter(Candidate::configured).toList();
        this.providers = usable.stream().map(Candidate::port).toList();
        this.names = usable.stream().map(Candidate::name).toList();

        if (providers.isEmpty()) {
            log.warn("No LLM provider is configured — every answer will be degraded");
        } else {
            log.info("LLM providers in order: {}", names);
        }
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return through(LlmPort::complete, systemPrompt, userPrompt);
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        return through(LlmPort::completeJson, systemPrompt, userPrompt);
    }

    /** Walk the chain with whichever call was asked for, so the standby honours it too. */
    private String through(TriFunction call, String systemPrompt, String userPrompt) {
        if (providers.isEmpty()) {
            throw new LlmUnavailableException("LLM not configured (no API key)", null);
        }
        LlmUnavailableException last = null;
        for (int i = 0; i < providers.size(); i++) {
            try {
                return call.apply(providers.get(i), systemPrompt, userPrompt);
            } catch (LlmUnavailableException e) {
                last = e;
                boolean hasStandby = i + 1 < providers.size();
                log.warn("LLM provider {} unavailable ({}){}", names.get(i), e.getMessage(),
                        hasStandby ? " — falling back to " + names.get(i + 1) : "");
            }
        }
        throw last;
    }

    /** Which of the port's two calls to walk the chain with. */
    @FunctionalInterface
    private interface TriFunction {
        String apply(LlmPort port, String systemPrompt, String userPrompt);
    }
}
