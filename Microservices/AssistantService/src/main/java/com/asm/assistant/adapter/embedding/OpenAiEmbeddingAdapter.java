package com.asm.assistant.adapter.embedding;

import com.asm.assistant.domain.port.EmbeddingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default embedding adapter: OpenAI {@code text-embedding-3-small} (1536 dims — matches the
 * {@code vector(1536)} column). Batches inputs per request.
 *
 * <p>When no API key is configured it reports {@link #isEnabled()} false and returns an empty list;
 * ingestion then persists chunks without vectors, and a later run (once the key is set) backfills
 * them. This keeps the pipeline runnable end-to-end before the key is provided.
 *
 * <p>Alternative provider: only loaded when {@code assistant.embedding.provider=openai}. The default
 * is Gemini ({@link GeminiEmbeddingAdapter}), reusing the one API key already configured.
 */
@Component
@ConditionalOnProperty(name = "assistant.embedding.provider", havingValue = "openai")
@Slf4j
public class OpenAiEmbeddingAdapter implements EmbeddingPort {

    private final String apiKey;
    private final String model;
    private final RestClient client;

    public OpenAiEmbeddingAdapter(
            @Value("${assistant.embedding.api-key:}") String apiKey,
            @Value("${assistant.embedding.model:text-embedding-3-small}") String model,
            @Value("${assistant.embedding.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String modelVersion() {
        return "openai:" + model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<float[]> embed(List<String> texts) {
        if (!isEnabled()) {
            log.warn("Embedding disabled (no OPENAI_API_KEY) — {} chunk(s) stored without vectors", texts.size());
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        int batch = 64;
        for (int i = 0; i < texts.size(); i += batch) {
            List<String> slice = texts.subList(i, Math.min(i + batch, texts.size()));
            Map<String, Object> resp = client.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of("model", model, "input", slice))
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            for (Map<String, Object> d : data) {
                List<Number> vec = (List<Number>) d.get("embedding");
                float[] arr = new float[vec.size()];
                for (int j = 0; j < vec.size(); j++) arr[j] = vec.get(j).floatValue();
                out.add(arr);
            }
        }
        return out;
    }
}
