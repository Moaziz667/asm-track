package com.asm.assistant.ingestion.parser;

import com.asm.assistant.ingestion.model.ParsedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One chunk per OpenAPI operation (path + method) rather than one blind chunk per file: each becomes
 * a compact, self-describing statement of the contract — method, path, summary, description,
 * parameters, request/response shape — which is what a question about "the API for X" needs to match.
 */
@Component
@Slf4j
public class OpenApiParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<ParsedChunk> parse(String json) {
        List<ParsedChunk> chunks = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("Unparseable OpenAPI document: {}", e.getMessage());
            return chunks;
        }
        JsonNode paths = root.path("paths");
        int ordinal = 0;
        Iterator<Map.Entry<String, JsonNode>> pit = paths.fields();
        while (pit.hasNext()) {
            Map.Entry<String, JsonNode> pe = pit.next();
            String path = pe.getKey();
            Iterator<Map.Entry<String, JsonNode>> mit = pe.getValue().fields();
            while (mit.hasNext()) {
                Map.Entry<String, JsonNode> me = mit.next();
                String method = me.getKey().toUpperCase();
                JsonNode op = me.getValue();
                String opId = op.path("operationId").asText("");
                String section = !opId.isBlank() ? opId : method + " " + path;
                chunks.add(new ParsedChunk(section, ordinal++, render(method, path, op)));
            }
        }
        return chunks;
    }

    private String render(String method, String path, JsonNode op) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append('\n');
        String summary = op.path("summary").asText("");
        String desc = op.path("description").asText("");
        if (!summary.isBlank()) sb.append("Résumé: ").append(summary).append('\n');
        if (!desc.isBlank()) sb.append("Description: ").append(desc).append('\n');
        JsonNode params = op.path("parameters");
        if (params.isArray() && params.size() > 0) {
            sb.append("Paramètres:");
            for (JsonNode p : params) {
                sb.append(' ').append(p.path("name").asText())
                  .append('(').append(p.path("in").asText()).append(')');
                String pd = p.path("description").asText("");
                if (!pd.isBlank()) sb.append(" — ").append(pd);
                sb.append(';');
            }
            sb.append('\n');
        }
        JsonNode responses = op.path("responses");
        if (responses.isObject() && responses.size() > 0) {
            sb.append("Réponses: ");
            responses.fieldNames().forEachRemaining(code -> sb.append(code).append(' '));
            sb.append('\n');
        }
        return sb.toString().strip();
    }
}
