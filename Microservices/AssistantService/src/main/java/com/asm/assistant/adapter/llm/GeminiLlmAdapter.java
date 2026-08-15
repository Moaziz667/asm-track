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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default LLM adapter: Google Gemini {@code generateContent}. Bounded by connect/read timeouts, with
 * bounded retry on transient 429/503 (honouring Google's {@code retryDelay}); on exhaustion it throws
 * {@link LlmUnavailableException} so the orchestrator returns a controlled error rather than hanging
 * or inventing an answer. The grounding rules are passed as a {@code systemInstruction}.
 */
@Component
@Slf4j
public class GeminiLlmAdapter implements LlmPort {

    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxOutputTokens;
    private final int maxRetries;
    private final RestClient client;
    private final CircuitBreaker breaker;
    private final RagMetrics metrics;

    public GeminiLlmAdapter(
            @Value("${assistant.llm.api-key:}") String apiKey,
            @Value("${assistant.llm.model:gemini-flash-latest}") String model,
            @Value("${assistant.llm.temperature:0.2}") double temperature,
            @Value("${assistant.llm.max-output-tokens:1024}") int maxOutputTokens,
            @Value("${assistant.llm.timeout-ms:20000}") long timeoutMs,
            @Value("${assistant.llm.max-retries:2}") int maxRetries,
            @Value("${assistant.llm.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            CircuitBreakerRegistry breakerRegistry,
            RagMetrics metrics) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.maxRetries = maxRetries;
        this.breaker = breakerRegistry.circuitBreaker("llm");
        this.metrics = metrics;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmUnavailableException("LLM not configured (no API key)", null);
        }
        // The breaker records the whole retrying call as one outcome; when open it fast-fails.
        try {
            return metrics.record(metrics.llmTimer,
                    () -> breaker.decorateSupplier(() -> callWithRetry(systemPrompt, userPrompt)).get());
        } catch (CallNotPermittedException e) {
            throw new LlmUnavailableException("LLM circuit open", e);
        }
    }

    @SuppressWarnings("unchecked")
    private String callWithRetry(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("parts", List.of(Map.of("text", userPrompt)))),
                "generationConfig", Map.of("temperature", temperature, "maxOutputTokens", maxOutputTokens)
        );

        for (int attempt = 0; ; attempt++) {
            try {
                Map<String, Object> resp = client.post()
                        .uri("/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                return extractText(resp);
            } catch (HttpServerErrorException | HttpClientErrorException.TooManyRequests e) {
                if (attempt >= maxRetries) throw new LlmUnavailableException("LLM failed after retries", e);
                long wait = retryDelayMs(bodyOf(e));
                log.warn("LLM transient error ({}); retry {}/{} in {} ms", statusOf(e), attempt + 1, maxRetries, wait);
                sleep(wait);
            } catch (Exception e) {
                throw new LlmUnavailableException("LLM call failed", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> resp) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            return ((String) parts.get(0).get("text")).strip();
        } catch (Exception e) {
            throw new LlmUnavailableException("Unexpected LLM response shape", e);
        }
    }

    private String bodyOf(Exception e) {
        if (e instanceof HttpServerErrorException se) return se.getResponseBodyAsString();
        if (e instanceof HttpClientErrorException ce) return ce.getResponseBodyAsString();
        return "";
    }

    private String statusOf(Exception e) {
        if (e instanceof HttpServerErrorException se) return se.getStatusCode().toString();
        if (e instanceof HttpClientErrorException ce) return ce.getStatusCode().toString();
        return "?";
    }

    private long retryDelayMs(String body) {
        Matcher m = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)").matcher(body == null ? "" : body);
        return m.find() ? (Long.parseLong(m.group(1)) + 1) * 1000L : 2000L;
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
