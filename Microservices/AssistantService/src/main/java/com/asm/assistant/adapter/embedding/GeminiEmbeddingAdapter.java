package com.asm.assistant.adapter.embedding;

import com.asm.assistant.domain.port.EmbeddingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default embedding adapter: Google {@code gemini-embedding-001} at {@code outputDimensionality=1536}
 * (Matryoshka), which matches the {@code vector(1536)} column — so switching to Gemini needed no
 * migration. Same API key as the LLM. Documents are embedded with {@code taskType=RETRIEVAL_DOCUMENT};
 * cosine distance is used downstream, so the (unnormalized) 1536-dim vectors need no normalization.
 *
 * <p>Selected when {@code assistant.embedding.provider=gemini} (the default). Disabled — returns an
 * empty list — when no key is set, so ingestion still persists chunks and backfills vectors later.
 */
@Component
@ConditionalOnProperty(name = "assistant.embedding.provider", havingValue = "gemini", matchIfMissing = true)
@Slf4j
public class GeminiEmbeddingAdapter implements EmbeddingPort {

    private final String apiKey;
    private final String model;
    private final int dimension;
    private final int batchSize;
    private final long interBatchSleepMs;
    private final int maxRetries;
    private final RestClient client;

    public GeminiEmbeddingAdapter(
            @Value("${assistant.embedding.api-key:}") String apiKey,
            @Value("${assistant.embedding.model:gemini-embedding-001}") String model,
            @Value("${assistant.embedding.dimension:1536}") int dimension,
            @Value("${assistant.embedding.rate-limit.batch-size:100}") int batchSize,
            @Value("${assistant.embedding.rate-limit.inter-batch-sleep-ms:62000}") long interBatchSleepMs,
            @Value("${assistant.embedding.rate-limit.max-retries:5}") int maxRetries,
            @Value("${assistant.embedding.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.batchSize = batchSize;
        this.interBatchSleepMs = interBatchSleepMs;
        this.maxRetries = maxRetries;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String modelVersion() {
        return "gemini:" + model + ":" + dimension;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<float[]> embed(List<String> texts) {
        if (!isEnabled()) {
            log.warn("Embedding disabled (no key) — {} chunk(s) stored without vectors", texts.size());
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        String modelPath = "models/" + model;
        int batches = (texts.size() + batchSize - 1) / batchSize;
        for (int b = 0, i = 0; i < texts.size(); i += batchSize, b++) {
            List<String> slice = texts.subList(i, Math.min(i + batchSize, texts.size()));
            // Free-tier quota is per-content-per-minute, so pace between batches to stay under it.
            if (b > 0 && interBatchSleepMs > 0) sleep(interBatchSleepMs);
            out.addAll(embedBatch(modelPath, slice, "RETRIEVAL_DOCUMENT"));
            log.info("Embedded batch {}/{} ({} chunks)", b + 1, batches, slice.size());
        }
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        if (!isEnabled()) return null;
        return embedBatch("models/" + model, List.of(text), "RETRIEVAL_QUERY").get(0);
    }

    @SuppressWarnings("unchecked")
    private List<float[]> embedBatch(String modelPath, List<String> slice, String taskType) {
        List<Map<String, Object>> requests = slice.stream().map(t -> Map.<String, Object>of(
                "model", modelPath,
                "content", Map.of("parts", List.of(Map.of("text", t))),
                "taskType", taskType,
                "outputDimensionality", dimension
        )).toList();

        for (int attempt = 0; ; attempt++) {
            try {
                Map<String, Object> resp = client.post()
                        // model id has no slash, so the path variable is encoding-safe; the "models/"
                        // prefix is a literal path segment (encoding it as %2F yields a 404).
                        .uri("/models/{model}:batchEmbedContents", model)
                        .header("x-goog-api-key", apiKey)
                        .body(Map.of("requests", requests))
                        .retrieve()
                        .body(Map.class);
                List<Map<String, Object>> embeddings = (List<Map<String, Object>>) resp.get("embeddings");
                List<float[]> out = new ArrayList<>(embeddings.size());
                for (Map<String, Object> e : embeddings) {
                    List<Number> vec = (List<Number>) e.get("values");
                    float[] arr = new float[vec.size()];
                    for (int j = 0; j < vec.size(); j++) arr[j] = vec.get(j).floatValue();
                    out.add(arr);
                }
                return out;
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= maxRetries) throw e;
                long wait = retryDelayMs(e.getResponseBodyAsString());
                log.warn("Embedding rate-limited (429); retrying in {} ms (attempt {}/{})", wait, attempt + 1, maxRetries);
                sleep(wait);
            }
        }
    }

    /** Parse Google's suggested {@code retryDelay} (e.g. "31s") from a 429 body; default 62s. */
    private long retryDelayMs(String body) {
        Matcher m = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)").matcher(body == null ? "" : body);
        return m.find() ? (Long.parseLong(m.group(1)) + 2) * 1000L : 62_000L;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding interrupted", ie);
        }
    }
}
