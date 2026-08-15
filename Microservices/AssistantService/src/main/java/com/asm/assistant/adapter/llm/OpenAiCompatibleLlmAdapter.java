package com.asm.assistant.adapter.llm;

import com.asm.assistant.domain.port.LlmPort;
import com.asm.assistant.observability.RagMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM adapter for any provider speaking the OpenAI {@code /chat/completions} shape — OpenRouter,
 * Groq, Hugging Face's router, Together, or a local Ollama. One adapter instead of one per vendor:
 * the base URL and model are configuration, so switching provider is an env change, not a code change.
 *
 * <p>Same contract as {@link GeminiLlmAdapter}: bounded timeouts, bounded retry on transient 429/5xx,
 * and {@link LlmUnavailableException} on exhaustion so the orchestrator degrades rather than invents.
 * It keeps its own circuit breaker — a provider being down must not open the breaker of its standby.
 */
@Component
@Slf4j
public class OpenAiCompatibleLlmAdapter implements LlmPort {

    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxOutputTokens;
    private final int maxRetries;
    private final RestClient client;
    private final CircuitBreaker breaker;
    private final RagMetrics metrics;

    public OpenAiCompatibleLlmAdapter(
            @Value("${assistant.llm.openai.api-key:}") String apiKey,
            @Value("${assistant.llm.openai.model:google/gemma-4-31b-it:free}") String model,
            @Value("${assistant.llm.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${assistant.llm.temperature:0.2}") double temperature,
            @Value("${assistant.llm.max-output-tokens:1024}") int maxOutputTokens,
            @Value("${assistant.llm.timeout-ms:20000}") long timeoutMs,
            @Value("${assistant.llm.max-retries:2}") int maxRetries,
            CircuitBreakerRegistry breakerRegistry,
            RagMetrics metrics) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.maxRetries = maxRetries;
        this.breaker = breakerRegistry.circuitBreaker("llm-openai");
        this.metrics = metrics;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Whether this provider is usable at all; lets the failover skip an unconfigured standby. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return run(systemPrompt, userPrompt, false);
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        return run(systemPrompt, userPrompt, true);
    }

    private String run(String systemPrompt, String userPrompt, boolean jsonOnly) {
        if (!isConfigured()) {
            throw new LlmUnavailableException("LLM not configured (no API key)", null);
        }
        try {
            return metrics.record(metrics.llmTimer,
                    () -> breaker.decorateSupplier(
                            () -> callWithRetry(systemPrompt, userPrompt, jsonOnly)).get());
        } catch (CallNotPermittedException e) {
            throw new LlmUnavailableException("LLM circuit open", e);
        }
    }

    /** A selection answer is a dozen tokens; capping it stops a degenerate reply running to the limit. */
    private static final int JSON_MAX_TOKENS = 120;

    @SuppressWarnings("unchecked")
    private String callWithRetry(String systemPrompt, String userPrompt, boolean jsonOnly) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", temperature,
                "max_tokens", jsonOnly ? JSON_MAX_TOKENS : maxOutputTokens
        ));
        // Gateways that don't know response_format ignore it; those that do stop the model from
        // answering with prose, a code fence, or the run of "!!!!!!" seen under load.
        if (jsonOnly) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        for (int attempt = 0; ; attempt++) {
            try {
                Map<String, Object> resp = client.post()
                        .uri("/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        // Optional OpenRouter attribution headers; ignored by other providers.
                        .header("X-Title", "ASM Assistant")
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                return extractText(resp);
            } catch (HttpServerErrorException | HttpClientErrorException.TooManyRequests e) {
                if (attempt >= maxRetries) throw new LlmUnavailableException("LLM failed after retries", e);
                log.warn("LLM transient error ({}); retry {}/{} in 2000 ms", statusOf(e), attempt + 1, maxRetries);
                sleep(2000);
            } catch (LlmUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmUnavailableException("LLM call failed", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> resp) {
        try {
            // Some gateways answer 200 with an error envelope instead of an HTTP error status.
            if (resp.get("choices") == null && resp.get("error") != null) {
                throw new LlmUnavailableException("LLM returned an error: " + resp.get("error"), null);
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String text = (String) message.get("content");
            if (text == null || text.isBlank()) {
                throw new LlmUnavailableException("LLM returned an empty answer", null);
            }
            return text.strip();
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmUnavailableException("Unexpected LLM response shape", e);
        }
    }

    private String statusOf(Exception e) {
        if (e instanceof HttpServerErrorException se) return se.getStatusCode().toString();
        if (e instanceof HttpClientErrorException ce) return ce.getStatusCode().toString();
        return "?";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM wait interrupted", ie);
        }
    }
}
